package actors.infra.cross

import actors.infra.Area.Shutdown
import actors.infra.cross.PedestrianCrossingRoutingAgent.PCRAConfig
import actors.infra.position.PedestrianConnectionPosition
import actors.infra.position.PedestrianPosition._
import actors.infra.{RoutingDataFromCross, RoutingDataFromRoad}
import akka.actor.{Actor, ActorRef, Props, Stash}
import controllers.Timing.Millis

object PedestrianCrossingRoutingAgent {
  def props() = Props(new PedestrianCrossingRoutingAgent())

  /**
   * Configures the PCRA.
   * @param dataSources map from the reference of the data sources (RRA)
   *                    to the [[position.PedestrianConnectionPosition]] of each
   * @param forwardTo sequence of references to the [[position.PedestrianConnectionPosition]]
   *                  and reference of destination RRAs
   * @param metric the (static) metric of the crossing
   */
  sealed case class PCRAConfig(dataSources: Map[ActorRef, PedestrianConnectionPosition],
                               forwardTo: Seq[(ActorRef, PedestrianConnectionPosition)],
                               metric: Millis)
}

/**
 * Handles data forwarding in a node of the routing graph, representing to a Crossing.
 * Handles data for pedestrian routes.
 */
sealed class PedestrianCrossingRoutingAgent private extends Actor with Stash {
  import context._

  def receive = waitingForConfig

  /**
   * `receive` function for the `waitingForConfig` state.
   * Waits for [[PedestrianCrossingRoutingAgent.PCRAConfig]].
   */
  def waitingForConfig: Receive = {
    case PCRAConfig(src, to, m) =>
      unstashAll()
      become(ready(src, to, m))

    case _ => stash()
  }

  /**
   * Transition function to the `ready` state.
   * @param dataSources map from the reference of the data sources (RRA)
   *                    to the [[position.PedestrianConnectionPosition]] of each
   * @param forwardTo sequence of references to the [[position.PedestrianConnectionPosition]]
   *                  and reference of destination RRAs
   * @param metric the (static) metric of the crossing
   * @return `receive` function for this state (handles data forwarding)
   */
  def ready(dataSources: Map[ActorRef, PedestrianConnectionPosition],
            forwardTo: Seq[(ActorRef, PedestrianConnectionPosition)],
            metric: Millis): Receive = {

    def forwardData(data: RoutingDataFromRoad, sender: ActorRef) {
      for ((ar, from) <- forwardTo if from != dataSources(sender))
        ar ! RoutingDataFromCross(
          data.data.map { case (to, (met, hops)) => to ->
            (met + metric * pedPosDiff(PCPtoPPD(from)._1, PCPtoPPD(dataSources(sender))._1),
              hops + 1)
          },
          dataSources(sender))
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
