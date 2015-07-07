package actors.infra.road

import actors.infra.Area.Shutdown
import actors.infra.Container._
import actors.infra._
import actors.infra.cross.CrossingBase._
import actors.infra.position.{ConnectionPosition, PedestrianConnectionPosition, TurnRef}
import actors.infra.road.Building.{PersonArriving, PersonFromBuilding}
import actors.infra.road.Navigator.{Arrived, NextTurn}
import actors.infra.road.ParkingLot.{CarArriving, CarFromParkingLot, PersonFromParkingLot}
import actors.infra.road.Road.{RoadId, RoadInitParams}
import actors.infra.road.RoadRoutingAgent.{RRAConfig, RoadQueueMetric}
import actors.notifications.EventBus.{Event, ThingExitedArea}
import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import com.typesafe.config.ConfigFactory
import controllers.Socket.{SendJson, JsonInbound}
import controllers.Timing.{Millis, Time}
import controllers.json.ActorRefSerializer.serAR
import controllers.json.{SetJamRequest, UnsetJamRequest}
import play.api.Configuration
import play.api.libs.json.{JsString, Json}
import things.Bus.BusLine
import things._

import scala.collection.convert.Wrappers.JListWrapper
import scala.collection.mutable
import scala.language.postfixOps

object Road {

  /**
   * @param fullName name of the Road
   * @param timeLength time to go through each [[Lane]] of this Road (in ticks)
   * @param capacity capacity of each [[Lane]] of this Road
   * @param pedestrianSlowdownFactor how much slower are the [[Sidewalk]]s w.r.t. the [[Lane]]s
   * @param buses specs of the buses initially in this Road
   * @param people specs of the people initially in this Road
   * @param parkingCapacity capacity of the parking of this Road (may be 0)
   * @param busConnections for each bus line that stops in this Road,
   *                       the [[RoadId]] of the previous stop,
   *                       the [[CrossingId]] of the crossing before the previous stop,
   *                       the [[position.ConnectionPosition]] of the road of the previous
   *                       stop relative to the previous crossing,
   *                       the [[BusLine]] identifier
   * @param routingTableSizes a tuple containing sizes for the vehicle, the pedestrian and
   *                          the reverse pedestrian routing tables.
   * @param eventBus the reference to the [[actors.notifications.EventBus]] for the area
   */
  private case class RoadInitParams(fullName: String,
                                    timeLength: Millis,
                                    capacity: Int,
                                    pedestrianSlowdownFactor: Int,
                                    buses: Seq[BusSpec],
                                    people: Seq[PersonSpec],
                                    parkingCapacity: Int,
                                    busConnections: Seq[(RoadId, CrossingId,
                                      ConnectionPosition, BusLine)],
                                    routingTableSizes: (Int, Int, Int),
                                    eventBus: ActorRef)

  /**
   * Prepares [[akka.actor.Props]] for a particular pair of roads (one for each direction),
   * or just one for one-way roads
   * @param configuration global configuration for the infrastructure
   * @param areaNum number of the area these Roads are in
   * @param roadNum number of these Roads
   * @param eventBus the reference to the [[actors.notifications.EventBus]] for the area
   * @return a sequence of tuples containing the name of each Road, Unit, and the Props
   */
  def getNamesAndProps(configuration: Configuration,
                       areaNum: Int,
                       roadNum: Int,
                       eventBus: ActorRef): Seq[(String, Unit, Props)] = {

    // utility functions
    def endSpec(conf: Configuration, area: String): (CrossingId, ConnectionPosition) = (
      CrossingId(areaId = conf.getString("aid").getOrElse(area),
        crossId = conf.getString("cid").get),
      ConnectionPosition(conf.getString("from").get.charAt(0)))

    def getRoadConf(areaNum: Int, roadNum: Int) =
      configuration.getConfigList("city.area").get.get(areaNum)
      .getConfigList("road").get.get(roadNum)

    def busConnSpec(conf: Configuration, area: String):
    (RoadId, CrossingId, ConnectionPosition, BusLine) = {
      val rid  = RoadId(areaId = conf.getString("aid").getOrElse(area),
        roadId = conf.getString("rid").get)
      val side = rid.roadId.charAt(rid.roadId.length-1)
      val rc   = getRoadConf(rid.areaId.toInt, rid.roadId.substring(0, rid.roadId.length-1).toInt)
      val cid  = endSpec(rc.getConfig(s"end$side").get, rid.areaId)._1
      val pos  = ConnectionPosition(conf.getString("from").get.charAt(0))
      val line = conf.getString("line").get
      (rid, cid, pos, line)
    }

    // get configuration of this road
    val numAreas = configuration.getConfigList("city.area").get.size()
    val roadConf = getRoadConf(areaNum, roadNum)
    val defaults = configuration.getConfig("defaults.road").get

    val oneWay = roadConf.getBoolean("oneWay").getOrElse(false)
    val fullName = roadConf.getString("fullName").get

    // get end paths
    val ((endAId: CrossingId, endACP: ConnectionPosition), (endBId, endBCP)) = {
      val ess = Seq("endA", "endB") map (s => endSpec(roadConf.getConfig(s).get, areaNum.toString))
      (ess.head, ess.last)
    }

    // check rules for roads connecting different areas
    val roadConnectsDifferentAreas = endAId.areaId != endBId.areaId
    if (roadConnectsDifferentAreas) {
      if (!oneWay)
        throw new Error(s"Road $areaNum-$roadNum connects different areas and must be one way")
      if (endAId.areaId != areaNum.toString)
        throw new Error(s"Road $areaNum-$roadNum connects different areas and must start from" +
          s" area $areaNum, not ${endAId.areaId}")
    }


    // calculate expected size of routing tables
    def numRoadsOfArea(areaId: Int): Int =
      (for (rc <- JListWrapper(configuration.
        getConfigList("city.area").get.get(areaId).getConfigList("road").get))
      yield if (rc.getBoolean("oneWay").getOrElse(false)) 1 else 2).sum

    def numRoadsToArea(areaId: Int) =
      (for ((ac, an) <- JListWrapper(configuration.getConfigList("city.area").get).zipWithIndex
            if an != areaId; // all other areas
            rc <- JListWrapper(ac.getConfigList("road").get) // all roads from other areas
            if endSpec(rc.getConfig("endB").get, an.toString)._1.areaId == areaId.toString)
      yield 1).sum

    val sizes: (Int, Int, Int) =
      if (!roadConnectsDifferentAreas) {
        val veh =
          (numAreas - 1 // other areas
            + numRoadsOfArea(areaNum) - 1) // other roads in the same area
        val rta = numRoadsToArea(areaNum) // roads to this area

        (veh, veh + rta, veh + rta)
      } else {
        val veh =
          (numAreas - 1 // other areas
            + numRoadsOfArea(endBId.areaId.toInt)) // roads in destination area

        (veh,
          veh + numRoadsToArea(endBId.areaId.toInt) - 1, // roads to destination area except self
          numAreas - 1 // other areas
            + numRoadsOfArea(endAId.areaId.toInt) - 1 // roads of reverse destination area except self
            + numRoadsToArea(endAId.areaId.toInt) // roads to reverse destination area
          )
      }

    // other side-specific configuration
    val roadIdA = RoadId(areaNum.toString, roadNum.toString + 'A')
    val roadIdB = RoadId(areaNum.toString, roadNum.toString + 'B')

    val (aConf: RoadInitParams, bConf: RoadInitParams) = {
      val confs = for ((side, roadId) <- Seq(('A', roadIdA), ('B', roadIdB))) yield {

        def getConfInt(key: String) =
          roadConf.getInt(s"$side.$key")
            .orElse(roadConf.getInt(key))
            .orElse(defaults.getInt(key)).get

        def getConfMillis(key: String) = new Millis(
          roadConf.getMilliseconds(s"$side.$key")
            .orElse(roadConf.getMilliseconds(key))
            .orElse(defaults.getMilliseconds(key)).get)

        val timeLength      = getConfMillis("length")
        val capacity        = getConfInt("capacity")
        val pedSlowFact     = getConfInt("pedestrianSlowdownFactor")
        val parkingCapacity = getConfInt("parkingCapacity")

        val buses: Seq[BusSpec] =
          roadConf.getConfigList(s"$side.buses").fold(Seq[BusSpec]())(cl =>
          JListWrapper(cl).map(BusSpec.fromConfiguration).toSeq)

        val people: Seq[PersonSpec] =
          roadConf.getConfigList(s"$side.people").fold(Seq[PersonSpec]())(cl =>
          JListWrapper(cl).map(PersonSpec.fromConfiguration(_, roadId)).toSeq)

        val busConnections: Seq[(RoadId, CrossingId, ConnectionPosition, BusLine)] =
          roadConf.getConfigList(s"$side.busConnections")
          .fold(Seq[(RoadId, CrossingId, ConnectionPosition, String)]())(cl =>
          JListWrapper(cl).map(busConnSpec(_, areaNum.toString)).toSeq)

        if (busConnections.map(_._3).distinct.size != busConnections.map(_._3).size) {
          throw new Error(s"Road $areaNum-$roadNum: duplicate bus lines")
        }

        RoadInitParams(
          fullName                 = fullName,
          timeLength               = timeLength,
          capacity                 = capacity,
          pedestrianSlowdownFactor = pedSlowFact,
          buses                    = buses,
          people                   = people,
          parkingCapacity          = parkingCapacity,
          busConnections           = busConnections,
          routingTableSizes        = sizes,
          eventBus                 = eventBus)
      }

      (confs.head, confs.last)
    }


    // create props
    val a = Props(new Road(
      fromId     = endAId,
      fromPos    = endACP,
      toId       = endBId,
      toPos      = endBCP,
      roadId     = roadIdA,
      initParams = aConf))

    lazy val b = Props(new Road(
      fromId     = endBId,
      fromPos    = endBCP,
      toId       = endAId,
      toPos      = endACP,
      roadId     = roadIdB,
      initParams = bConf))

    val an = roadNum.toString + 'A'
    val bn = roadNum.toString + 'B'

    if (oneWay) {
      Seq((an, (), a))
    } else {
      Seq((an, (), a), (bn, (), b))
    }
  }









  private val akkaBasePath = ConfigFactory.load.getString("infra.global.akkaBasePath")

  /**
   * A generic identifier used in routing.
   */
  sealed trait RoutingId

  /**
   * An identifier for a specific (abstract or not) destination.
   */
  sealed trait RoutableId extends RoutingId

  /**
   * An identifier of an [[Area]].
   * @param areaId the id
   */
  sealed case class AreaId(areaId: String) extends RoutingId

  /**
   * An identifier of a [[Road]].
   * @param areaId the id of the Area
   * @param roadId the id of the Road (includes a number and the direction, i.e. 'A' or 'B')
   */
  sealed case class RoadId(areaId: String, roadId: String) extends RoutingId with RoutableId {
    def containingArea = AreaId(areaId)

    def path = s"$akkaBasePath/user/city/area/$areaId/road/$roadId"
  }

  /**
   * An identifier for an abstract routing definition
   */
  sealed trait AdditionalRoutingInformation extends RoutingId with RoutableId

  /**
   * An abstract routing identifier for the nearest road with a non-full parking
   */
  case object NearestFreeParking extends AdditionalRoutingInformation




  // messages
  sealed case class RoadConf(vehicleRA: ActorRef, pedestrianRA: ActorRef, busPedRA: ActorRef)
  sealed case class RoadConfWithLanes(vehicleRA: ActorRef, pedestrianRA: ActorRef,
                                      busPedRA: ActorRef, laneSelectors: Seq[LaneSelector])
  sealed case class RoadConfBus(busRA: ActorRef, line: BusLine)
  case object CrossingReceivedLanesAndSidewalk
  case object CrossingReceivedSidewalk

  case object StartRouting
  case object RoutingFull
  case object StartThings
  private case object LanesCreated
  private case object LanesDown

  sealed case class Jam(factor: Int)

  case object RoadJammed extends Event
  case object RoadNoLongerJammed extends Event
}






/**
 * Handles vehicle traffic from `fromId` to `toId`, and pedestrian traffic in both directions.
 * @param fromId id of the `from` crossing
 * @param toId id of the `to` crossing
 * @param fromPos connection position of this Road relative to the `from` crossing
 * @param toPos connection position of this Road relative to the `to` crossing
 * @param roadId id of this Road
 * @param initParams parameters for this Road
 */
sealed class Road private (fromId: CrossingId, toId: CrossingId,
                           fromPos: ConnectionPosition, toPos: ConnectionPosition,
                           roadId: RoadId, initParams: RoadInitParams
                            ) extends Actor with ActorLogging {
  import actors.infra.road.Road._
  import context._
  import initParams._

  override val supervisorStrategy = AllForOneStrategy() {case _ => Escalate}

  val vehicleRoutingAgent           = actorOf(RoadRoutingAgent.props(), "vRA")
  val pedestrianRoutingAgentForward = actorOf(RoadRoutingAgent.props(), "pRAf")
  val pedestrianRoutingAgentReverse = actorOf(RoadRoutingAgent.props(), "pRAr")
  val busPedRoutingAgentForward     = actorOf(RoadRoutingAgent.props(), "bRAf")
  val busPedRoutingAgentReverse     = actorOf(RoadRoutingAgent.props(), "bRAr")

  val vehicleNavigator           = new RoadNavigator[ConnectionPosition](roadId)
  val pedestrianNavigatorForward = new RoadNavigator[PedestrianConnectionPosition](roadId)
  val pedestrianNavigatorReverse = new RoadNavigator[PedestrianConnectionPosition](roadId)
  val busPedNavigatorForward     = new RoadNavigator[PedestrianConnectionPosition](roadId)
  val busPedNavigatorReverse     = new RoadNavigator[PedestrianConnectionPosition](roadId)

  case class BusLineData(roadId: RoadId, crossId: CrossingId, connPos: ConnectionPosition,
                         line: BusLine, routingAgent: ActorRef,
                         navigator: RoadNavigator[PedestrianConnectionPosition])

  val busLines =
  for ((rid, cid, pos, line) <- busConnections) yield BusLineData(
    rid, cid, pos, line, 
    routingAgent = actorOf(RoadRoutingAgent.props(), s"bRA$line"),
    navigator = new RoadNavigator[PedestrianConnectionPosition](roadId))

  val busDestinationToAgent: Map[RoutingId, ActorRef] = busLines.map{
    case bld if bld.roadId.areaId == roadId.areaId =>
      bld.roadId -> bld.routingAgent
    case bld =>
      bld.roadId.containingArea -> bld.routingAgent
  }.toMap
  
  val busAgentToNavigator: Map[ActorRef, RoadNavigator[PedestrianConnectionPosition]] = busLines.map(
    bld => bld.routingAgent -> bld.navigator).toMap

  if (busLines.nonEmpty) {
    actorOf(BusStop.props(self), "busStop")
  }

  def updateNavigator(sender: ActorRef, rtu: RoutingTableUpdate[TurnRef]) {
    type RtuCp = RoutingTableUpdate[ConnectionPosition]
    type RtuPcp = RoutingTableUpdate[PedestrianConnectionPosition]

    // the casts are necessary due to type erasure
    sender match {
      case `vehicleRoutingAgent` =>
        vehicleNavigator.update(rtu.asInstanceOf[RtuCp])

        // send metric data to bus links
        for ((dest, (_, met)) <- rtu.data if busDestinationToAgent.contains(dest))
          busDestinationToAgent(dest) ! RoadQueueMetric(met)

      case `pedestrianRoutingAgentForward` =>
        pedestrianNavigatorForward.update(rtu.asInstanceOf[RtuPcp])
      case `pedestrianRoutingAgentReverse` =>
        pedestrianNavigatorReverse.update(rtu.asInstanceOf[RtuPcp])
      case `busPedRoutingAgentForward` =>
        busPedNavigatorForward.update(rtu.asInstanceOf[RtuPcp])
      case `busPedRoutingAgentReverse` =>
        busPedNavigatorReverse.update(rtu.asInstanceOf[RtuPcp])
      case rra if busAgentToNavigator.contains(rra) =>
        busAgentToNavigator(rra).update(rtu.asInstanceOf[RtuPcp])
    }
  }


  /**
   * Starts the registration process with the Crossings
   */
  override def preStart() {
    actorSelection(fromId.path) ! RegisterRoadOut(
      position        = fromPos,
      roadId          = roadId,
      vehicleRAOut    = vehicleRoutingAgent,
      pedestrianRAOut = pedestrianRoutingAgentForward,
      pedestrianRAIn  = pedestrianRoutingAgentReverse,
      busPedRAOut     = busPedRoutingAgentForward,
      busPedRAIn      = busPedRoutingAgentReverse,
      addBusVehRAOut  = busLines.map(_.routingAgent)
    )

    actorSelection(toId.path) ! RegisterRoadIn(
      position        = toPos,
      roadId          = roadId,
      vehicleRAIn     = vehicleRoutingAgent,
      pedestrianRAIn  = pedestrianRoutingAgentForward,
      pedestrianRAOut = pedestrianRoutingAgentReverse,
      busPedRAIn      = busPedRoutingAgentForward,
      busPedRAOut     = busPedRoutingAgentReverse
    )

    for ((rid, cid, cp, line) <- busConnections;
         bra = busLines.find(_.line == line).get.routingAgent)
      actorSelection(cid.path) ! RegisterRoadBus(
        position = cp,
        line = line,
        busPedRAIn = bra
      )
  }

  /** The initial state is `waitingForConf`.*/
  def receive = waitingForConf

  /**
   * Transition function to the `waitingForConf` state. Completes the registration process.
   * @return `receive` function for this state.
   */
  def waitingForConf: Receive = {
    case class ConnectedCrossing(cross: ActorRef, vehicleRA: ActorRef,
                                 pedestrianRA: ActorRef, busPedRA: ActorRef)

    var from: ConnectedCrossing = null
    var to: ConnectedCrossing = null
    var laneSelectors: Seq[LaneSelector] = null
    var laneActors: Seq[(LaneSelector, ActorRef)] = null
    var sidewalkForward: ActorRef = null
    var sidewalkReverse: ActorRef = null
    val busCrosses = mutable.Map[BusLine, ActorRef]()
    var lanesReceived = false
    var sidewalkReceived = false

    def completed = from != null && to != null &&
      laneActors != null && sidewalkForward != null && sidewalkReverse != null &&
      busCrosses.size == busConnections.size && lanesReceived && sidewalkReceived

    def becomeWFR() {
      vehicleRoutingAgent ! RRAConfig(
        dataDestination  = (fromId, from.vehicleRA),
        dataSource       = (toId, to.vehicleRA),
        roadId           = roadId,
        routingTableSize = routingTableSizes._1,
        initialMetric    = timeLength)

      pedestrianRoutingAgentForward ! RRAConfig(
        dataDestination  = (fromId, from.pedestrianRA),
        dataSource       = (toId, to.pedestrianRA),
        roadId           = roadId,
        routingTableSize = routingTableSizes._2,
        initialMetric    = timeLength * pedestrianSlowdownFactor)

      pedestrianRoutingAgentReverse ! RRAConfig(
        dataDestination  = (toId, to.pedestrianRA),
        dataSource       = (fromId, from.pedestrianRA),
        roadId           = roadId,
        routingTableSize = routingTableSizes._3,
        initialMetric    = timeLength * pedestrianSlowdownFactor)

      busPedRoutingAgentForward ! RRAConfig(
        dataDestination  = (fromId, from.busPedRA),
        dataSource       = (toId, to.busPedRA),
        roadId           = roadId,
        routingTableSize = routingTableSizes._2,
        initialMetric    = timeLength * pedestrianSlowdownFactor)

      busPedRoutingAgentReverse ! RRAConfig(
        dataDestination  = (toId, to.busPedRA),
        dataSource       = (fromId, from.busPedRA),
        roadId           = roadId,
        routingTableSize = routingTableSizes._3,
        initialMetric    = timeLength * pedestrianSlowdownFactor)

      busLines.foreach(bld =>
        bld.routingAgent ! RRAConfig(
          dataDestination  = (fromId, from.busPedRA),
          dataSource       = (busConnections.find(_._4 == bld.line).get._2, busCrosses(bld.line)),
          roadId           = roadId,
          routingTableSize = Int.MaxValue, // no full check for bus routing
          initialMetric    = new Millis(10000)))

      val dis = new VehicleLaneDispatcher(laneActors, roadId)

      become(waitingForRouting(from.cross, to.cross, laneActors.map(_._2),
        sidewalkForward, sidewalkReverse, dis))

      val info = Json.obj(
        "fullName"       -> fullName,
        "from"           -> serAR(from.cross),
        "to"             -> serAR(to.cross),
        "lengthInMillis" -> timeLength,
        "capacity"       -> capacity,
        "isBusStop"      -> busLines.nonEmpty,
        "hasPark"        -> (parkingCapacity != 0)
      )

      parent ! ContainedInfo(info)
      parent ! ContainedReceived
    }

    {
      // configuration: FROM
      case RoadConf(vra, pra, bra) =>
        from = ConnectedCrossing(sender, vra, pra, bra)

        sidewalkReverse = actorOf(Sidewalk.getProps(
          length        = timeLength * pedestrianSlowdownFactor,
          to            = from.cross,
          eventBus      = eventBus
        ), "SWr")

        from.cross ! RegisterSidewalk(fromPos, sidewalkReverse)

      case CrossingReceivedSidewalk =>
        sidewalkReceived = true

        if (completed)
          becomeWFR()

      // configuration: TO
      case RoadConfWithLanes(vra, pra, bra, ls) =>
        to = ConnectedCrossing(sender, vra, pra, bra)
        laneSelectors = ls

        sidewalkForward = actorOf(Sidewalk.getProps(
          length        = timeLength * pedestrianSlowdownFactor,
          to            = to.cross,
          eventBus      = eventBus
        ), "SWf")

        val loadManager = actorOf(RoadLoadManager.props(
          roadRoutingAgent = vehicleRoutingAgent,
          initialMetric = timeLength,
          laneCount = laneSelectors.size), "loadManager")

        val namesAndProps: Seq[(String, LaneSelector, Props)] =
          ls.zipWithIndex map {case (sel, i) =>
            (i.toString, sel, Lane.getProps(
              index         = i,
              selector      = sel,
              length        = timeLength,
              capacity      = capacity,
              to            = to.cross,
              road          = self,
              loadManager   = loadManager,
              eventBus      = eventBus))
          }

        actorOf(
          Container.props(namesAndProps = namesAndProps, createdMessage = LanesCreated),
          "lane")

      case ContainerPopulated(LanesCreated, map) =>
        laneActors = laneSelectors map (sel => (sel, map(sel)))
        to.cross ! RegisterLanesAndSidewalk(toPos, laneActors, sidewalkForward)

      case CrossingReceivedLanesAndSidewalk =>
        lanesReceived = true
        if (completed)
          becomeWFR()

      // configuration: bus links
      case RoadConfBus(bra, line) =>
        busCrosses(line) = bra
        if (completed)
          becomeWFR()
    }
  }


  /**
   * Transition function to the `waitingForRouting` state. Completes the routing startup process.
   * @param from reference to the `from` crossing
   * @param to reference to the `to` crossing
   * @param lanes references to the [[Lane]]s of this Road
   * @param sidewalkForward reference to the forward [[Sidewalk]]
   * @param sidewalkReverse reference to the reverse [[Sidewalk]]
   * @param dispatcher the [[VehicleLaneDispatcher]] of this Road
   * @return `receive` function for this state.
   */
  def waitingForRouting(from: ActorRef, to: ActorRef, lanes: Seq[ActorRef],
                        sidewalkForward: ActorRef, sidewalkReverse: ActorRef,
                        dispatcher: VehicleLaneDispatcher): Receive = {

    val ras = Seq(vehicleRoutingAgent,
      pedestrianRoutingAgentForward, pedestrianRoutingAgentReverse,
      busPedRoutingAgentForward, busPedRoutingAgentReverse) ++ busLines.map(_.routingAgent)
    var rraFull: Int = 0

    {
      case StartRouting =>
        ras.foreach(_ ! RoadRoutingAgent.StartRouting)

      case RoutingFull =>
        rraFull += 1
        if (rraFull == 5) {
          parent ! ContainedReceived
          become(waitingForStart(from, to, lanes, sidewalkForward, sidewalkReverse, dispatcher))
        }

      case rtu: RoutingTableUpdate[TurnRef] =>
        updateNavigator(sender, rtu)
    }
  }


  /**
   * Transition function to the `waitingForStart` state. Waits for the [[Road.StartThings]] command.
   * @param from reference to the `from` crossing
   * @param to reference to the `to` crossing
   * @param lanes references to the [[Lane]]s of this Road
   * @param sidewalkForward reference to the forward [[Sidewalk]]
   * @param sidewalkReverse reference to the reverse [[Sidewalk]]
   * @param dispatcher the [[VehicleLaneDispatcher]] of this Road
   * @return `receive` function for this state.
   */
  def waitingForStart(from: ActorRef, to: ActorRef, lanes: Seq[ActorRef],
                      sidewalkForward: ActorRef, sidewalkReverse: ActorRef,
                      dispatcher: VehicleLaneDispatcher): Receive = {

    case rtu: RoutingTableUpdate[TurnRef] =>
      updateNavigator(sender, rtu)

    case StartThings =>
      parent ! ContainedReceived
      become(operational(from, to, lanes, sidewalkForward, sidewalkReverse, dispatcher))
  }


  /**
   * Transition function to the `operational` state. Prepares [[Building]], [[ParkingLot]] and [[PedestrianManager]].
   * @param from reference to the `from` crossing
   * @param to reference to the `to` crossing
   * @param lanes references to the [[Lane]]s of this Road
   * @param sidewalkForward reference to the forward [[Sidewalk]]
   * @param sidewalkReverse reference to the reverse [[Sidewalk]]
   * @param dispatcher the [[VehicleLaneDispatcher]] of this Road
   * @return `receive` function for this state (routes vehicles and pedestrians).
   */
  def operational(from: ActorRef, to: ActorRef, lanes: Seq[ActorRef],
                  sidewalkForward: ActorRef, sidewalkReverse: ActorRef,
                  dispatcher: VehicleLaneDispatcher): Receive = {

    val building = actorOf(Building.props(eventBus), "build")
    people.foreach(ps => building ! PersonArriving(ps.makePerson))

    val parkingLot = actorOf(ParkingLot.props(
      road         = roadId,
      routingAgent = vehicleRoutingAgent,
      capacity     = parkingCapacity,
      initialCars  = people.collect{
        case PersonSpec(personId, _, Some((carId, _))) =>
          ParkedCarSpec(id = carId, ownerId = personId)},
      eventBus     = eventBus),
      "park")

    val stoppingBusLines: Set[BusLine] = busLines.map(_.line).toSet


    val guards: mutable.Map[ActorRef, Boolean] = mutable.Map() ++ lanes.map(_ -> true)
    var crossGuard: Boolean = true
    var parkGuard: Boolean = true
    var vehBuffer: Option[(Vehicle, ConnectionPosition, ActorRef)] = None
    var lastSentTimestamp: Time = Time.now

    def sendVehicle(lane: ActorRef, vehicle: Vehicle, nextTurn: ConnectionPosition, t: Time) {
      if (t > lastSentTimestamp) {
        lane ! VehicleToLane(vehicle, nextTurn, t)
        lastSentTimestamp = t
      } else {
        lane ! VehicleToLane(vehicle, nextTurn, lastSentTimestamp)
      }
    }

    def setGuard(sender: ActorRef, t: Time) {
      if (vehBuffer.exists(_._3 == sender)) {
        val (v, nt, l) = vehBuffer.get
        sendVehicle(l, v, nt, t)
        vehBuffer = None

      } else {
        guards(sender) = true

        if (guards.values.forall(_ == true) && vehBuffer.isEmpty) {
          if (!crossGuard) {
            from ! CanSendMore(t)
            crossGuard = true
          }
          if (!parkGuard) {
            parkingLot ! CanSendMore(t)
            parkGuard = true
          }
        }
      }
    }

    def routeCar(car: Car, t: Time) {
      vehicleNavigator.getDirection(car.destination) match {
        case Arrived =>
          sender ! CanSendMore(t)
          parkingLot ! CarArriving(car, t)

        case nt: NextTurn[ConnectionPosition] =>
          forwardVehicle(car, nt.turn, t)
      }
    }

    def forwardVehicleT(t: (Vehicle, ConnectionPosition), timestamp: Time) =
      forwardVehicle(t._1, t._2, timestamp)
    def forwardVehicle(veh: Vehicle, nextTurn: ConnectionPosition, timestamp: Time) {
      val lane = dispatcher.laneFor(veh, nextTurn)
      if (guards(lane)) {
        guards(lane) = false
        sendVehicle(lane, veh, nextTurn, timestamp)
      } else if (vehBuffer.isEmpty) {
        vehBuffer = Some((veh, nextTurn, lane))
      } else {
        throw new Error(s"${serAR(self)}: Received vehicle with false guard and full buffer, dropping $veh")
      }
    }


    val pedestrianManager = new PedestrianManager(
      routeBus               = (bus: Bus, t: Time) => forwardVehicleT(bus.nextTurn(), t),
      road                   = roadId,
      parkingLot             = parkingLot,
      building               = building,
      sidewalkForward        = sidewalkForward,
      sidewalkReverse        = sidewalkReverse,
      sidewalkTime           = timeLength * pedestrianSlowdownFactor,
      pedNavigatorForward    = pedestrianNavigatorForward,
      pedNavigatorReverse    = pedestrianNavigatorReverse,
      busPedNavigatorForward = busPedNavigatorForward,
      busPedNavigatorReverse = busPedNavigatorReverse,
      busNavigators          = busLines.map(l => l.line -> l.navigator).toMap,
      actor                  = self,
      log                    = log,
      eventBus               = eventBus)


    case object JamEnd
    var jamEnd: Option[Cancellable] = None

    def unsetJam() {
      jamEnd foreach (_.cancel())
      jamEnd = None
      setJam(1, 1, None)
      eventBus ! RoadNoLongerJammed
    }

    def setJam(vehJamSlowdown: Int, pedJamSlowdown: Int, duration: Option[Millis]) {
      log.info(s"jammed ($vehJamSlowdown, $pedJamSlowdown)")

      lanes                                 foreach (_ ! Jam(vehJamSlowdown))
      Seq(sidewalkForward, sidewalkReverse) foreach (_ ! Jam(pedJamSlowdown))

      Seq(pedestrianRoutingAgentForward, pedestrianRoutingAgentReverse,
        busPedRoutingAgentForward, busPedRoutingAgentReverse) foreach (
        _ ! RoadQueueMetric(timeLength * pedestrianSlowdownFactor * pedJamSlowdown))

      duration foreach { d =>
        system.scheduler.scheduleOnce(d.fd, self, JamEnd)(executor = context.dispatcher)
        eventBus ! RoadJammed
      }
    }

    def checkAreaChange(thing: RoadThing, from: CrossingId, sender: ActorRef) {
      if (roadId.containingArea != from.containingArea)
        sender ! ThingExitedArea(thing, from.containingArea, roadId.containingArea)
    }

    buses.foreach(b => forwardVehicleT(b.makeBus.nextTurn(), Time.now))


    {
      // from RRA
      case rtu: RoutingTableUpdate[TurnRef] =>
        updateNavigator(sender, rtu)


      // from Lanes
      case CanSendMore(t) =>
        setGuard(sender, t)


      // from Crossings
      case VehicleFromCrossing(car: Car, t) =>
        crossGuard = false
        checkAreaChange(car, fromId, sender)
        routeCar(car, t)

      case VehicleFromCrossing(bus: Bus, t) =>
        crossGuard = false
        checkAreaChange(bus, fromId, sender)
        if (stoppingBusLines contains bus.line)
          pedestrianManager.receiveBus(bus, t)
        else
          forwardVehicleT(bus.nextTurn(), t)

      case PedestrianFromCrossing(ped, t) if sender == from =>
        checkAreaChange(ped, fromId, sender)
        pedestrianManager.forwardPedestrianForward(ped, t)

      case PedestrianFromCrossing(ped, t) if sender == to =>
        checkAreaChange(ped, toId, sender)
        pedestrianManager.forwardPedestrianReverse(ped, t)


      // from Parking/Building
      case PersonFromBuilding(person, t) =>
        pedestrianManager.forwardPersonFormBuilding(person, t)

      case PersonFromParkingLot(person, t) =>
        pedestrianManager.forwardPersonFromParking(person, t)

      case CarFromParkingLot(car, t) =>
        parkGuard = false
        routeCar(car, t)


      // from BusStop
      case BusStop.GetPedestriansWaitingForBus(snd, cbk) =>
        sender ! BusStop.PedestriansWaitingForBus(
          pedestrianManager.getPedestrians, snd, cbk)


      // from Client
      case SetJamRequest(v, p, d) =>
        setJam(v, p, Some(d))

      case UnsetJamRequest() =>
        unsetJam()

      case JamEnd =>
        unsetJam()


      case Shutdown =>
        children.filterNot(_ == child("lane").get) foreach (_ ! Shutdown)
        child("lane").get ! SendToContained(Shutdown, LanesDown)

      case LanesDown =>
        become(down)
        parent ! ContainedReceived

      case JsonInbound(_, _) =>
        sender ! SendJson(JsString(s"guards = $guards"))
    }
  }

  /**
   * Transition function to the `down` state.
   * @return `receive` function for this state (ignores all messages).
   */
  def down: Receive = {
    case _ =>
  }
}
