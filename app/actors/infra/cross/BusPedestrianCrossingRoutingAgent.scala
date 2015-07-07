package actors.infra.cross

import actors.infra.Area.Shutdown
import actors.infra._
import actors.infra.cross.BusPedestrianCrossingRoutingAgent.BPCRAConfig
import actors.infra.position.PedestrianPosition._
import actors.infra.position._
import akka.actor.{Actor, ActorRef, Props, Stash}
import controllers.Timing.Millis

object BusPedestrianCrossingRoutingAgent {
  def props() = Props(new BusPedestrianCrossingRoutingAgent())

  /**
   * Configures the BPCRA.
   * @param pedestrianDataSources map from the reference of pedestrian data sources
   *                              to the [[position.PedestrianConnectionPosition]] of each
   * @param pedestrianForwardTo sequence of references to the [[position.PedestrianConnectionPosition]]
   *                            and reference of pedestrian destination RRAs
   * @param busDataSources map from the reference of bus data sources (RRA)
   *                       to the [[position.ConnectionPosition]] of each
   * @param busForwardTo sequence of references to the [[position.ConnectionPosition]]
   *                     and reference of bus destination RRAs
   * @param metric the (static) metric of the crossing
   */
  sealed case class BPCRAConfig(pedestrianDataSources: Map[ActorRef, PedestrianConnectionPosition],
                                pedestrianForwardTo:  Seq[(ActorRef, PedestrianConnectionPosition)],
                                busDataSources: Map[ActorRef, ConnectionPosition],
                                busForwardTo:  Seq[(ActorRef, ConnectionPosition)],
                                metric: Millis)
}

/**
 * Handles data forwarding in a node of the routing graph, representing to a Crossing.
 * Handles data for pedestrian and bus routes.
 */
sealed class BusPedestrianCrossingRoutingAgent private extends Actor with Stash {
  import context._

  def receive = waitingForConfig

  /**
   * `receive` function for the `waitingForConfig` state.
   * Waits for [[BusPedestrianCrossingRoutingAgent.BPCRAConfig]].
   */
  def waitingForConfig: Receive = {
    case BPCRAConfig(a, b, c, d, e) =>
      unstashAll()
      become(ready(a, b, c, d, e))

    case _ => stash()
  }

  /**
   * Transition function to the `ready` state.
   * @param pedestrianDataSources map from the reference of pedestrian data sources
   *                              to the [[position.PedestrianConnectionPosition]] of each
   * @param pedestrianForwardTo sequence of references to the [[position.PedestrianConnectionPosition]]
   *                            and reference of pedestrian destination RRAs
   * @param busDataSources map from the reference of bus data sources (RRA)
   *                       to the [[position.ConnectionPosition]] of each
   * @param busForwardTo sequence of references to the [[position.ConnectionPosition]]
   *                     and reference of bus destination RRAs
   * @param metric the (static) metric of the crossing
   * @return `receive` function for this state (handles data forwarding)
   */
  def ready(pedestrianDataSources: Map[ActorRef, PedestrianConnectionPosition],
            pedestrianForwardTo:  Seq[(ActorRef, PedestrianConnectionPosition)],
            busDataSources: Map[ActorRef, ConnectionPosition],
            busForwardTo:  Seq[(ActorRef, ConnectionPosition)],
            metric: Millis): Receive = {

    val pedestrianDataSourcesWTB = pedestrianDataSources.map { case (ar, pcp) => ar ->
      PedestrianConnectionPosition(pcp.position, pcp.side, useBusLink = None)}

    val extendedBusForwardTo = pedestrianForwardTo ++ busForwardTo.map {
      case (ar, cp) => ar -> PedestrianConnectionPosition(cp, LeftS)}
    val combinedDataSources = pedestrianDataSourcesWTB ++ busDataSources.map {
      case (ar, cp) => ar -> PedestrianConnectionPosition(cp, RightS, useBusLink = Some(ar))}

    /**
     * Increase data using pedestrian logic
     * @param data the data
     * @param pcpTo the data source (pedestrians will go towards it)
     * @param pcpFrom the data receiver (pedestrians will come from it)
     */
    def increasePedestrianData(data: RoutingDataFromRoad,
                               pcpTo: PedestrianConnectionPosition,
                               pcpFrom: PedestrianConnectionPosition) =
      RoutingDataFromCross(
        data.data.map{case (path, (met, hops)) => path ->
          (met + metric * pedPosDiff(PCPtoPPD(pcpFrom)._1, PCPtoPPD(pcpTo)._1),
            hops + 1)},
        pcpTo)

    /**
     * Increase data using car logic
     * @param data the data
     * @param cpTo the data source (vehicles will go towards it)
     * @param cpFrom the data receiver (vehicles will come from it)
     */
    def increaseCarData(data: RoutingDataFromRoad, cpTo: ConnectionPosition, cpFrom: ConnectionPosition) =
      RoutingDataFromCross(
        data.data.map{case (path, (met, hops)) => path ->
          (met + metric, hops + 1)},
        cpTo)


    /**
     * Handles data forwarding
     * @param data the data
     * @param sender the actor sending the data
     */
    def forwardData(data: RoutingDataFromRoad, sender: ActorRef) {
      for ((ar, pcpFrom) <- pedestrianForwardTo
           if pcpFrom != combinedDataSources(sender)) { // bus->pedestrian, pedestrian->pedestrian
        ar ! increasePedestrianData(data, combinedDataSources(sender), pcpFrom)
      }

      if (busDataSources.contains(sender)) { // bus->bus
        for ((ar, cpFrom) <- busForwardTo) {
          ar ! increaseCarData(data, busDataSources(sender), cpFrom)
        }

      } else { // pedestrian->bus
        for ((ar, pcpFrom) <- extendedBusForwardTo
             if pcpFrom != combinedDataSources(sender)) {
          assert(pcpFrom != combinedDataSources(sender))
          ar ! increasePedestrianData(data, pedestrianDataSourcesWTB(sender), pcpFrom)
        }
      }
    }


    {
      case data: RoutingDataFromRoad =>
        forwardData(data, sender)

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
