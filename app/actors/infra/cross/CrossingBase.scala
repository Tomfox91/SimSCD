package actors.infra.cross

import actors.infra.Container.ContainedReceived
import actors.infra._
import actors.infra.cross.BusPedestrianCrossingRoutingAgent.BPCRAConfig
import actors.infra.cross.CrossingBase._
import actors.infra.cross.PedestrianCrossingRoutingAgent.PCRAConfig
import actors.infra.cross.VehicleCrossingRoutingAgent.VCRAConfig
import actors.infra.position._
import actors.infra.road.Road._
import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import com.typesafe.config.ConfigFactory
import controllers.Timing.Millis
import play.api.Configuration
import things.Bus.BusLine
import things.Vehicle
import things.Vehicle.VehicleCategory

import scala.collection.convert.Wrappers.JListWrapper
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object CrossingBase {
  /**
   * Prepares [[akka.actor.Props]] for a Crossing. Selects the suitable subclass of Crossing and prepares its Props.
   * @param configuration the global configuration for the infrastructure
   * @param areaId the id of the area the Crossing is in
   * @param crossId the id of the crossing within the Area
   * @param eventBus reference to the [[actors.notifications.EventBus]] of this Area
   */
  def getProps(configuration: Configuration,
               areaId: Int,
               crossId: Int,
               eventBus: ActorRef): Props = {

    val crossConf =
      configuration.getConfigList("city.area").get.get(areaId)
        .getConfigList("cross").get.get(crossId)
    val defaults = configuration.getConfig("defaults.cross").get

    val id = CrossingId(areaId.toString, crossId.toString)

    val (x, y) = {
      val pos = crossConf.getDoubleList("pos").get
      (pos.get(0), pos.get(1))
    }

    val transitTime = new Millis(crossConf.getMilliseconds("transitTime").orElse(defaults.getMilliseconds("transitTime")).get)
    val pedestrianCrossingTime = new Millis(crossConf.getMilliseconds("pedestrianCrossingTime").orElse(defaults.getMilliseconds("pedestrianCrossingTime")).get)

    val tYpe: String = crossConf.getString("type").orElse(defaults.getString("type")).get

    val lanesCP = crossConf.getConfig("lanes").orElse(defaults.getConfig("lanes")).get
    val laneSelectors: Map[ConnectionPosition, Seq[LaneSelector]] =
      lanesCP.keys.map{(d: String) =>
      val lanesList = JListWrapper(lanesCP.getConfigList(d).get)

      (ConnectionPosition(d.charAt(0)), lanesList map {(lane: Configuration) =>
        LaneSelector(
          position = lane.getString("dir").get match {
            case "*" => None
            case s => Some(s.toSeq map ConnectionPosition.apply)
          },

          category = lane.getString("cat").get match {
            case "*" => None
            case s => Some(Vehicle.categoryFor(s))
          }
        )
      })
    }.toMap

    val prioritiesCP = crossConf.getConfig("priorities").orElse(defaults.getConfig("priorities")).get
    val priorities: Map[ConnectionPosition, Int] = prioritiesCP.keys.map{(d: String) =>
      (ConnectionPosition(d.charAt(0)), prioritiesCP.getInt(d).get)
    }.toMap

    val lightTimingCP = JListWrapper(
      crossConf.getConfigList("lightTiming").orElse(defaults.getConfigList("lightTiming")).get)
    val lightTiming: Seq[TurnSpec] = lightTimingCP.map{c => TurnSpec(
      greenVehicles    = c.getString("veh").get.map(ConnectionPosition.apply).toSet,
      greenPedestrians = c.getString("ped").get.map(ConnectionPosition.apply).toSet,
      duration         = new Millis(c.getMilliseconds("duration").get))
    }.toSeq

    tYpe match {
      case "priority" =>
        PriorityCrossing.getProps(crossId = id, x = x, y = y, laneSelectors = laneSelectors,
          transitTime = transitTime, pedestrianCrossingTime = pedestrianCrossingTime,
          priorities = priorities, eventBus = eventBus)
      case "lights" =>
        TrafficLightCrossing.getProps(crossId = id, x = x, y = y, laneSelectors = laneSelectors,
          transitTime = transitTime, pedestrianCrossingTime = pedestrianCrossingTime,
          priorities = priorities, lightTiming = lightTiming, eventBus = eventBus)
    }
  }



  private val akkaBasePath = ConfigFactory.load.getString("infra.global.akkaBasePath")


  /**
   * Specification of the vehicles and pedestrians that may pass in a particular turn.
   * @param greenVehicles The position of the roads whose vehicles may pass
   * @param greenPedestrians The position of the roads whose pedestrians may pass
   * @param duration duration of the turn, in ticks
   */
  sealed case class TurnSpec(greenVehicles:    Set[ConnectionPosition],
                             greenPedestrians: Set[ConnectionPosition],
                             duration: Millis) {
    val redPedestrians = ConnectionPosition.allCPs.toSet diff greenPedestrians
    val redVehicles = ConnectionPosition.allCPs.toSet diff greenVehicles
  }


  /**
   * An identifier of a [[CrossingBase]].
   * @param areaId the id of the Area
   * @param crossId the id of the Crossing
   */
  sealed case class CrossingId(areaId: String, crossId: String) {
    def containingArea = AreaId(areaId)

    def path = s"$akkaBasePath/user/city/area/$areaId/cross/$crossId"
  }




  sealed case class LaneSelector(position: Option[Seq[ConnectionPosition]],
                                 category: Option[VehicleCategory])

  sealed case class RegisterRoadIn (position:        ConnectionPosition,
                                    roadId:          RoadId,
                                    vehicleRAIn:     ActorRef,
                                    pedestrianRAIn:  ActorRef,
                                    pedestrianRAOut: ActorRef,
                                    busPedRAIn:      ActorRef,
                                    busPedRAOut:     ActorRef)
  sealed case class RegisterRoadOut(position:        ConnectionPosition,
                                    roadId:          RoadId,
                                    vehicleRAOut:    ActorRef,
                                    pedestrianRAOut: ActorRef,
                                    pedestrianRAIn:  ActorRef,
                                    busPedRAOut:     ActorRef,
                                    busPedRAIn:      ActorRef,
                                    addBusVehRAOut:  Iterable[ActorRef])
  sealed case class RegisterRoadBus(position:        ConnectionPosition,
                                    line:            BusLine,
                                    busPedRAIn:      ActorRef)

  sealed case class RegisterLanesAndSidewalk(position: ConnectionPosition,
                                             lanes: Seq[(LaneSelector, ActorRef)],
                                             sidewalk: ActorRef)
  sealed case class RegisterSidewalk(position: ConnectionPosition,
                                     sidewalk: ActorRef)
  case object AllRoadsRegistered

  private[cross] type LaneId = Int
}


/**
 * Manages traffic coming from different Roads and forwards it.
 * @param crossId id of this Crossing
 * @param transitTime time needed by a vehicle to go through the crossing
 * @param pedestrianCrossingTime time needed by a pedestrian to cross the road
 * @param laneSelectors for each [[position.ConnectionPosition]],
 *                      the sequence of lane descriptors [[CrossingBase.LaneSelector]] on that side
 * @param eventBus reference to the [[actors.notifications.EventBus]] of this Area
 */
abstract class CrossingBase (crossId: CrossingId,
                             laneSelectors: Map[ConnectionPosition, Seq[LaneSelector]],
                             transitTime: Millis, pedestrianCrossingTime: Millis,
                             eventBus: ActorRef) extends Actor with ActorLogging {
  import context._

  override val supervisorStrategy = AllForOneStrategy() {case _ => Escalate}

  val vehicleRoutingAgent    = actorOf(VehicleCrossingRoutingAgent.props(), "vRA")
  val pedestrianRoutingAgent = actorOf(PedestrianCrossingRoutingAgent.props(), "pRA")
  val pedBusRoutingAgent     = actorOf(BusPedestrianCrossingRoutingAgent.props(), "bRA")

  /**
   * Sends the parent [[Container.ContainedReceived]].
   */
  override def preStart() {
    parent ! ContainedReceived
  }

  /** The initial state is `waitingForRoads`. */
  final def receive = waitingForRoads


  /**
   * Transition function to the `waitingForRoads` state.
   * @return `receive` function for this state (handles the registration of roads to this Crossing)
   */
  private def waitingForRoads: Receive = {
    val vehicleRoutingOut    = mutable.ListBuffer[(ActorRef, ConnectionPosition)]()
    val vehicleRoutingIn     = mutable.ListBuffer[ActorRef]()
    val pedestrianRoutingOut = mutable.ListBuffer[(ActorRef, PedestrianConnectionPosition)]()
    val pedestrianRoutingIn  = mutable.ListBuffer[(ActorRef, PedestrianConnectionPosition)]()
    val busPedRoutingOut     = mutable.ListBuffer[(ActorRef, PedestrianConnectionPosition)]()
    val busPedRoutingIn      = mutable.ListBuffer[(ActorRef, PedestrianConnectionPosition)]()
    val busVehRoutingOut     = mutable.ListBuffer[(ActorRef, ConnectionPosition)]()
    val busVehRoutingIn      = mutable.ListBuffer[(ActorRef, ConnectionPosition)]()

    val roadsConnectedIn     = mutable.ListBuffer[(ConnectionPosition, RoadId, ActorRef)]()
    val lanesConnectedIn     = mutable.ListBuffer[(ConnectionPosition, Seq[(LaneSelector, ActorRef)])]()
    val roadsConnectedOut    = mutable.ListBuffer[(ConnectionPosition, RoadId, ActorRef)]()
    val sidewalksConnectedIn = mutable.ListBuffer[(ConnectionPosition, ActorRef, Side)]()
    val roadIdOfConnected    = mutable.ListBuffer[(ActorRef, RoadId)]()

    {
      case rro: RegisterRoadOut =>
        roadsConnectedOut    += ((rro.position, rro.roadId, sender))

        vehicleRoutingOut    += rro.vehicleRAOut -> rro.position
        pedestrianRoutingOut += rro.pedestrianRAOut -> PedestrianConnectionPosition(rro.position, RightS)
        pedestrianRoutingIn  += rro.pedestrianRAIn  -> PedestrianConnectionPosition(rro.position, RightS)
        busPedRoutingOut     += rro.busPedRAOut     -> PedestrianConnectionPosition(rro.position, RightS)
        busPedRoutingIn      += rro.busPedRAIn      -> PedestrianConnectionPosition(rro.position, RightS)
        busVehRoutingOut    ++= rro.addBusVehRAOut.map(_ -> rro.position)

        sender ! RoadConf(vehicleRoutingAgent, pedestrianRoutingAgent, pedBusRoutingAgent)

      case rri: RegisterRoadIn =>
        roadsConnectedIn     += ((rri.position, rri.roadId, sender))

        vehicleRoutingIn     += rri.vehicleRAIn
        pedestrianRoutingIn  += rri.pedestrianRAIn  -> PedestrianConnectionPosition(rri.position, LeftS)
        pedestrianRoutingOut += rri.pedestrianRAOut -> PedestrianConnectionPosition(rri.position, LeftS)
        busPedRoutingIn      += rri.busPedRAIn      -> PedestrianConnectionPosition(rri.position, LeftS)
        busPedRoutingOut     += rri.busPedRAOut     -> PedestrianConnectionPosition(rri.position, LeftS)

        sender ! RoadConfWithLanes(vehicleRoutingAgent, pedestrianRoutingAgent, pedBusRoutingAgent,
          laneSelectors(rri.position))

      case RegisterLanesAndSidewalk(pos, lanes, side) =>
        val roadId = roadsConnectedIn.find(_._1 == pos).get._2
        lanesConnectedIn     += pos -> lanes
        roadIdOfConnected   ++= lanes.map(_._2 -> roadId)
        sidewalksConnectedIn += ((pos, side, LeftS))
        roadIdOfConnected    += side -> roadId
        sender ! CrossingReceivedLanesAndSidewalk

      case RegisterSidewalk(pos, side) =>
        val roadId = roadsConnectedOut.find(_._1 == pos).get._2
        sidewalksConnectedIn += ((pos, side, RightS))
        roadIdOfConnected    += side -> roadId
        sender ! CrossingReceivedSidewalk

      case RegisterRoadBus(pos, line, braIn) =>
        busVehRoutingIn += braIn -> pos
        sender ! RoadConfBus(pedBusRoutingAgent, line)

      case AllRoadsRegistered =>
        vehicleRoutingAgent ! VCRAConfig(
          dataSources           = vehicleRoutingOut.toMap,
          forwardTo             = vehicleRoutingIn.result(),
          metric                = transitTime)
        pedestrianRoutingAgent ! PCRAConfig(
          dataSources           = pedestrianRoutingOut.toMap,
          forwardTo             = pedestrianRoutingIn.result(),
          metric                = pedestrianCrossingTime)
        pedBusRoutingAgent ! BPCRAConfig(
          pedestrianDataSources = busPedRoutingOut.toMap,
          pedestrianForwardTo   = busPedRoutingIn.result(),
          busDataSources        = busVehRoutingOut.toMap,
          busForwardTo          = busVehRoutingIn.result(),
          metric                = pedestrianCrossingTime)

        parent ! ContainedReceived

        val lanes: ListBuffer[((ConnectionPosition, LaneId), ActorRef)] =
          for ((cp, lg) <- lanesConnectedIn;
               ((ls, ar), i) <- lg.zipWithIndex)
          yield ((cp, i), ar)

        val cc = new CrossingContext(roadIdOf = roadIdOfConnected.toMap,
      vehicleLanesIn =  lanes.toMap,
      vehicleLanesInRev = lanes.map(_.swap).toMap,
      vehicleRoadsOut = roadsConnectedOut.map{case (cp, id, ar) => cp -> ar}.toMap,
      vehicleRoadsOutRev = roadsConnectedOut.map(r => r._3 -> r._1).toMap,
      pedestrianSidewalksInRev = sidewalksConnectedIn.map{case (cp, ar, s) => ar -> PedestrianConnectionPosition(cp, s)}.toMap,
      pedestrianRoadsOut = (roadsConnectedIn.map{case (cp, id, ar) =>
        PedestrianConnectionPosition(cp, LeftS) -> ar} ++
        roadsConnectedOut.map{case (cp, id, ar) =>
          PedestrianConnectionPosition(cp, RightS) -> ar}).toMap)


        become(ready(cc))
    }
  }

  /**
   * Contains data from the startup phase.
   * @param roadIdOf a map from the actor references of the Roads to their [[road.Road.RoadId]]
   * @param vehicleLanesIn a map from the [[position.ConnectionPosition]] and `LaneId`
   *                       of the incoming Lanes to their actor references
   * @param vehicleLanesInRev a map from the actor references of the incoming Lanes
   *                          to their [[position.ConnectionPosition]] and `LaneId`
   * @param vehicleRoadsOut a map from the [[position.ConnectionPosition]] of the outgoing Lanes
   *                           to their actor references
   * @param vehicleRoadsOutRev a map from the actor references of the outgoing Lanes
   *                           to their [[position.ConnectionPosition]]
   * @param pedestrianSidewalksInRev a map from the actor reference of the incoming sidewalks to
   *                                 their [[position.PedestrianConnectionPosition]]
   * @param pedestrianRoadsOut a map from the [[position.PedestrianConnectionPosition]] of the exiting roads
   *                           to their actor reference
   */
  protected case class CrossingContext(roadIdOf: Map[ActorRef, RoadId],
                                       vehicleLanesIn: Map[(ConnectionPosition, LaneId), ActorRef],
                                       vehicleLanesInRev: Map[ActorRef, (ConnectionPosition, LaneId)],
                                       vehicleRoadsOut: Map[ConnectionPosition, ActorRef],
                                       vehicleRoadsOutRev: Map[ActorRef, ConnectionPosition],
                                       pedestrianSidewalksInRev: Map[ActorRef, PedestrianConnectionPosition],
                                       pedestrianRoadsOut: Map[PedestrianConnectionPosition, ActorRef])

  /**
   * Transition function to the `ready` state.
   * @return `receive` function for this state
   */
  protected def ready(crossingContext: CrossingContext): Receive

  /**
   * Transition function to the `down` state.
   * @return `receive` function for this state (ignores all messages)
   */
  final protected def down: Receive = {
    case _ =>
  }
}
