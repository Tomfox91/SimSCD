package actors.infra.cross

import actors.infra.Area.Shutdown
import actors.infra.cross.VehicleCrossingRoutingAgent.VCRAConfig
import actors.infra.position.ConnectionPosition
import actors.infra.road.Road.RoutingId
import actors.infra.{RoutingDataFromCross, RoutingDataFromRoad}
import akka.actor.{Actor, ActorRef, Props, Stash}
import controllers.Timing.Millis

object VehicleCrossingRoutingAgent {
  def props() = Props(new VehicleCrossingRoutingAgent())

  /**
   * Configures the VCRA.
   * @param dataSources map from the reference of the data sources (RRA)
   *                    to the [[position.ConnectionPosition]] of each
   * @param forwardTo list of RRAs to forward updates to
   * @param metric the (static) metric of the crossing
   */
  sealed case class VCRAConfig(dataSources: Map[ActorRef, ConnectionPosition],
                              forwardTo: Iterable[ActorRef],
                              metric: Millis)
}

/**
 * Handles data forwarding in a node of the routing graph, representing to a Crossing.
 * Handles data for vehicle routes.
 */
sealed class VehicleCrossingRoutingAgent private extends Actor with Stash {
  import context._

  def receive = waitingForConfig

  /**
   * `receive` function for the `waitingForConfig` state.
   * Waits for [[VehicleCrossingRoutingAgent.VCRAConfig]].
   */
  def waitingForConfig: Receive = {
    case VCRAConfig(src, to, m) =>
      unstashAll()
      become(ready(src, to, m))

    case _ => stash()
  }

  /**
   * Transition function to the `ready` state.
   * @param dataSources map from the reference of the data sources (RRA)
   *                    to the [[position.ConnectionPosition]] of each
   * @param forwardTo list of RRAs to forward updates to
   * @param metric the (static) metric of the crossing
   * @return `receive` function for this state (handles data forwarding)
   */
  def ready(dataSources: Map[ActorRef, ConnectionPosition],
            forwardTo: Iterable[ActorRef],
            metric: Millis): Receive = {

    def forwardData(data: RoutingDataFromRoad, sender: ActorRef) {
      val increasedData: Map[RoutingId, (Millis, Int)] =
        data.data map {case (path, (met, hops)) => path -> (met + metric, hops + 1)}

      val toSend = RoutingDataFromCross(increasedData, dataSources(sender))

      forwardTo foreach (_ ! toSend)
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
