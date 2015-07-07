package actors.notifications

import actors.infra.position.PedestrianPosition
import actors.infra.road.Road.AreaId
import actors.notifications.EventBus.Event
import akka.actor.{Actor, ActorRef, Terminated}
import controllers.Timing.Millis
import controllers.json.ActorRefSerializer.serAR
import controllers.json.{JsonConversions, SubscribeRequest, UnsubscribeRequest}
import play.api.Logger
import things.RoadThing

import scala.collection.mutable

object EventBus {
  trait Event

  case class ThingEnteredStructure(thing: RoadThing, containerType: String,
                                   duration: Option[Millis] = None,
                                   vertex: Option[PedestrianPosition] = None
                                    ) extends Event

  case class ThingExitedArea(thing: RoadThing, fromArea: AreaId, toArea: AreaId)
    extends Event
}

/**
. * Manages notifications from infrastructure actors to the clients
 */
sealed class EventBus extends Actor {
  import context._

  val subscribers = mutable.Map[ActorRef, String]()

  def receive = {
    /**
     * Manages subscription requests from clients
     */
    case SubscribeRequest(callback, ip) =>
      if (subscribers contains sender)
        Logger.warn(s"Duplicate subscription from $ip (${sender.path})")
      else {
        subscribers += (sender -> callback)
        sender ! SubscribeRequest.response(callback, self)
        watch(sender)
      }

    /**
     * Manages unsubscription requests from clients
     */
    case UnsubscribeRequest(callback) =>
      subscribers -= sender
      sender ! UnsubscribeRequest.response(callback, self)
      unwatch(sender)

    /**
     * Forwards event notifications
     */
    case e: Event =>
      for ((dest, cbk) <- subscribers) {
        dest ! JsonConversions.eventToJson(e, cbk, sender)
      }

    /**
     * Manages an unexpected socket termination
     */
    case Terminated(socketActor) =>
      Logger.info(s"EventBus ${serAR(self)} automatically unsubscribing $socketActor")
      subscribers -= socketActor
  }
}
