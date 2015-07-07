package actors.infra.road

import actors.infra.Area.Shutdown
import actors.infra._
import actors.infra.cross.CrossingBase._
import actors.infra.position.TurnRef
import actors.infra.road.Road._
import actors.infra.road.RoadRoutingAgent._
import akka.actor.{Actor, ActorRef, Props, Stash}
import com.typesafe.config.ConfigFactory
import controllers.Timing._
import controllers.json.ActorRefSerializer.serAR

import scala.collection.mutable

object RoadRoutingAgent {
  def props[TR <: TurnRef]() = Props(new RoadRoutingAgent[TR]())

  /**
   *
   * @param dataDestination the CRA this RRA sends data to
   * @param dataSource the CRA this RRA receives data from
   * @param roadId the id of the road owning this RRA
   * @param routingTableSize the precomputed table size
   * @param initialMetric the initial metric
   */
  sealed case class RRAConfig(dataDestination: (CrossingId, ActorRef),
                              dataSource: (CrossingId, ActorRef),
                              roadId: RoadId,
                              routingTableSize: Int,
                              initialMetric: Millis)

  case object StartRouting
  sealed case class RoadQueueMetric(metric: Millis)
  sealed case class PublishAdditionalRoutingInformation(info: AdditionalRoutingInformation)
  sealed case class UnPublishAdditionalRoutingInformation(info: AdditionalRoutingInformation)

  private val communicationThreshold =
    new Millis(ConfigFactory.load.getMilliseconds("infra.global.routingAgent.communicationThreshold"))
  private val maxDistanceThreshold =
    new Millis(ConfigFactory.load.getMilliseconds("infra.global.routingAgent.maxDistance"))
  private val metricInf: Millis = Millis.MaxValue - maxDistanceThreshold
  private val hopsInf: Int = Int.MaxValue - 100
  private val inf = (metricInf, hopsInf)
}

/**
 * Handles updates of an edge in the routing graph, usually corresponding to a road.
 * @tparam TR the type of the routing information
 *            (usually [[position.ConnectionPosition]] or [[position.PedestrianConnectionPosition]])
 */
sealed class RoadRoutingAgent[TR <: TurnRef] private extends Actor with Stash {
  import context._
  val myPath = serAR(self)

  /** initial state is `waitingForConfig` */
  def receive = waitingForConfig

  /**
   * `receive` function for the `waitingForConfig` state. Waits for [[RoadRoutingAgent.RRAConfig]].
   */
  def waitingForConfig: Receive = {
    case RRAConfig(to, from, rid, s, m) =>
      unstashAll()
      become(waitingForStart(to, from, rid, s, m))

    case _ => stash()
  }

  /**
   * `receive` function for the `waitingForStart` state. Waits for [[RoadRoutingAgent.StartRouting]]
   * @param to the id and reference of the CRA this RRA sends data to
   * @param from the id and reference of the CRA this RRA receives data from
   * @param roadId the id of the road owning this RRA
   * @param routingTableSize the precomputed table size
   * @param initialMetric the initial metric
   */
  def waitingForStart(to: (CrossingId, ActorRef), from: (CrossingId, ActorRef),
                      roadId: RoadId, routingTableSize: Int,
                      initialMetric: Millis): Receive = {

    case RoadRoutingAgent.StartRouting =>
      unstashAll()
      become(ready(to, from, roadId, routingTableSize, initialMetric))

    case _ => stash()
  }

  /**
   * `receive` function for the `ready` state. Handles routing updates.
   * @param to the id and reference of the CRA this RRA sends data to
   * @param from the id and reference of the CRA this RRA receives data from
   * @param roadId the id of the road owning this RRA
   * @param routingTableSize the precomputed table size
   * @param initialMetric the initial metric
   */
  def ready(to: (CrossingId, ActorRef), from: (CrossingId, ActorRef),
            roadId: RoadId, routingTableSize: Int,
            initialMetric: Millis): Receive = {
    val (toId, toRef) = to
    val (fromId, _) = from
    val connectsDifferentAreas = toId.areaId != fromId.areaId

    var metric: Millis = initialMetric


    /**
     * distanceTable(dest)(firstTurn) contains the distance from the current
     * position to dest via firstTurn.
     */
    val distanceTable = mutable.Map[RoutingId, mutable.Map[TR, (Millis, Int)]]()


    /**
     * routingTable(dest) contains the distance to dest on the best route,
     * and the fist turn to follow such route.
     */
    val routingTable = mutable.Map[RoutingId, (TR, Millis, Int)]()

    /**
     * additionalInfo contains additional routing info on self (like NearestFreeParking)
     * that the RRA is currently publishing.
     */
    val additionalInfo = mutable.Set[AdditionalRoutingInformation]()

    def updateDistances(updateData: RoutingDataFromCross[TR]) {
      val updated = new mutable.SetBuilder[RoutingId, Set[RoutingId]](Set())

      for ((path, (newMetric, newHops)) <- updateData.data
           if path != roadId && path != fromId.containingArea) {

        distanceTable
          .getOrElseUpdate(path, mutable.Map())
          .update(updateData.nextTurn, (newMetric, newHops))

        updated += path
      }

      updateRouting(updated.result())
    }

    def updateRouting(updated: Iterable[RoutingId]) {
      val toSendToRoad =
        new mutable.MapBuilder[RoutingId, (TR, Millis),
          Map[RoutingId, (TR, Millis)]](Map())
      val toForward =
        new mutable.MapBuilder[RoutingId, (Millis, Int), Map[RoutingId, (Millis, Int)]](Map())
      var exceedsThreshold = false

      updated foreach {path =>
        val (old, oldMetric, _) =
          routingTable.getOrElse(path, (null, metricInf, hopsInf))
        val (best, (bestMetric, bestHops)) =
          distanceTable(path).minBy(_._2._1)

        if ((old, oldMetric) != (best, bestMetric)) {
          routingTable(path) = (best, bestMetric, bestHops)
          toSendToRoad += path -> (best, bestMetric + metric)
          toForward    += path -> (bestMetric + metric, bestHops)

          if ((bestMetric |-| oldMetric) >= communicationThreshold) {
            exceedsThreshold = true
          }
        }
      }

      sendRoutingToRoad(toSendToRoad.result())

      if (exceedsThreshold)
        forwardRoutingData(toForward.result())
    }

    def updateMetric(newMetric: Millis) {
      val oldMetric = metric
      metric = newMetric

      sendOwn()

      if ((newMetric |-| oldMetric) > communicationThreshold) {
        // resend the whole table
        val toForward =
          new mutable.MapBuilder[RoutingId, (Millis, Int),
            Map[RoutingId, (Millis, Int)]](Map())
        routingTable foreach {case (path, (_, met, hops)) =>
            toForward += path -> (met + metric, hops)
        }
        forwardRoutingData(toForward.result())
      }
    }

    def forwardRoutingData(res: Map[RoutingId, (Millis, Int)]) {
      val toSend = res filter {
        case (ari: AdditionalRoutingInformation, _) => ! additionalInfo.contains(ari)
        case _ => true
      } map {
        case (path, (met, hops)) if met > maxDistanceThreshold => path -> inf
        case o => o
      }

      if (!connectsDifferentAreas) {
        if (toSend.nonEmpty)
          toRef ! RoutingDataFromRoad(toSend)
      } else {
        //send only distances to areas and ARI
        val nonRoads = toSend filterNot (_._1.isInstanceOf[RoadId])
        if (nonRoads.nonEmpty)
          toRef ! RoutingDataFromRoad(nonRoads)
      }
    }

    def sendRoutingToRoad(res: Map[RoutingId, (TR, Millis)]) {
      if (res.nonEmpty)
        parent ! RoutingTableUpdate(res, additionalInfo.toSet)
    }


    def sendOwn() {
      if (!connectsDifferentAreas) {
        toRef ! RoutingDataFromRoad(Map(
          roadId -> (Millis.Zero, 0)
        ))
      } else {
        toRef ! RoutingDataFromRoad(Map(
            roadId -> (Millis.Zero, 0),
            fromId.containingArea -> (metric, 1)
        ))
      }
    }

    def publishAdditionalRoutingInformation(info: AdditionalRoutingInformation) {
      additionalInfo += info
      parent ! RoutingTableUpdate[TR](Map(), additionalInfo.toSet)

      toRef ! RoutingDataFromRoad(Map(
        info -> (Millis.Zero, 0)
      ))
    }

    def unPublishAdditionalRoutingInformation(info: AdditionalRoutingInformation) {
      additionalInfo -= info
      parent ! RoutingTableUpdate[TR](Map(), additionalInfo.toSet)

      toRef ! RoutingDataFromRoad(Map(
        info -> routingTable.get(info).map(t => (t._2 + metric, t._3)).getOrElse(inf)
      ))
    }

    sendOwn()
    var full = false

    {
      case data: RoutingDataFromCross[TR] =>
        updateDistances(data)

        val routingSize = routingTable.count( ! _._1.isInstanceOf[AdditionalRoutingInformation])

        if (!full && routingSize == routingTableSize) {
          full = true
          parent ! Road.RoutingFull
        }

      case RoadQueueMetric(m) =>
        updateMetric(m)

      case PublishAdditionalRoutingInformation(info) =>
        publishAdditionalRoutingInformation(info)

      case UnPublishAdditionalRoutingInformation(info) =>
        unPublishAdditionalRoutingInformation(info)

      case Shutdown =>
        become(down)
    }
  }

  /**
   * `receive` function for the `down` state. Ignores all messages.
   */
  def down: Receive = {
    case _ =>
  }
}
