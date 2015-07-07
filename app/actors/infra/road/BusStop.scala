package actors.infra.road

import actors.infra.road.BusStop.{GetPedestriansWaitingForBus, PedestriansWaitingForBus}
import akka.actor.{Actor, ActorRef, Props}
import controllers.json.GetContainedRequest
import things.Pedestrian


object BusStop {
  def props(road: ActorRef): Props = Props(new BusStop(road))

  sealed case class GetPedestriansWaitingForBus(sender: ActorRef, callback: String)
  sealed case class PedestriansWaitingForBus(pedestrians: Seq[Pedestrian],
                                             sender: ActorRef, callback: String)
}


/**
 * Relays [[controllers.json.GetContainedRequest]]s to the road.
 * @param road the road that owns this bus stop
 */
class BusStop(road: ActorRef) extends Actor {
  override def receive: Receive = {
    case GetContainedRequest(cbk) =>
      road ! GetPedestriansWaitingForBus(sender, cbk)

    case PedestriansWaitingForBus(ped, snd, cbk) =>
      snd ! GetContainedRequest.response(self, cbk, ped)
  }
}
