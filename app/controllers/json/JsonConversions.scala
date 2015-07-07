package controllers.json

import actors.infra.City
import actors.infra.cross.TrafficLightCrossing
import actors.infra.road.{PedestrianManager, Road}
import actors.notifications.EventBus
import actors.notifications.EventBus.Event
import akka.actor.ActorRef
import akka.serialization.Serialization
import controllers.Socket.{JsonInbound, SendJson}
import controllers.Timing.Millis
import controllers.json.ActorRefSerializer.serAR
import play.api.libs.json._
import things._

object JsonConversions {
  /**
   * Converts a vehicle or a pedestrian to a [[JsObject]]
   * @param thing vehicle or pedestrian to convert
   */
  private[json] def thingToJson(thing: RoadThing): JsObject = {
    thing match {
      case p: Pedestrian => thingToJson(p.person)

      case p: PersonWithCar => Json.obj(
        "type"   -> "pedestrian",
        "id"     -> p.id,
        "carId"  -> p.carId,
        "info"   -> p.infoString)
      case p: PersonWithoutCar => Json.obj(
        "type"   -> "pedestrian",
        "id"     -> p.id,
        "carId"  -> JsNull,
        "info"   -> p.infoString)

      case car: CarLike => Json.obj(
        "type"   -> "car",
        "id"     -> car.id,
        "owner"  -> car.ownerId)
      case bus: Bus => Json.obj(
        "type"       -> "bus",
        "id"         -> bus.id,
        "line"       -> bus.line,
        "occupants"  -> bus.passengers.map(_.id)
      )
    }
  }

  /**
   * Converts an event to a [[play.api.libs.json.JsObject]]
   * @param e event to convert
   * @param callback callback used by the client
   * @param sender actor sending the event
   */
  def eventToJson(e: Event, callback: String, sender: ActorRef): SendJson =
    SendJson(e match {
      case EventBus.ThingEnteredStructure(thing, contType, duration, vertex) => Json.obj(
        "event" -> "thingEnteredStructure",
        "callback" -> callback,
        "thing" -> thingToJson(thing),
        "container" -> Json.obj(
          "type" -> contType,
          "id" -> serAR(sender))
      ) ++
        duration.fold(ifEmpty = Json.obj())(d => Json.obj("duration" -> d)) ++
        vertex.fold(ifEmpty = Json.obj())(pp => Json.obj("vertex" ->
          s"${pp.ns.toChar}${pp.we.toChar}"))

      case EventBus.ThingExitedArea(thing, fromArea, toArea) => Json.obj(
        "event" -> "thingExitedArea",
        "callback" -> callback,
        "thing" -> thingToJson(thing),
        "from" -> fromArea.areaId,
        "to" -> toArea.areaId
      )

      case TrafficLightCrossing.ChangeLight(veh, ped) => Json.obj(
        "event" -> "changeLights",
        "callback" -> callback,
        "cross" -> serAR(sender),
        "roads" -> veh.map(_.toChar.toString),
        "pedestrians" -> ped.map(_.toChar.toString)
      )

      case PedestrianManager.PedestrianTakingBus(ped, bus) => Json.obj(
        "event" -> "pedestrianTakingBus",
        "callback" -> callback,
        "thing" -> thingToJson(ped),
        "bus" -> thingToJson(bus),
        "road" -> serAR(sender)
      )

      case Road.RoadJammed => Json.obj(
        "event" -> "roadJammed",
        "callback" -> callback,
        "road" -> serAR(sender)
      )

      case Road.RoadNoLongerJammed => Json.obj(
        "event" -> "roadNoLongerJammed",
        "callback" -> callback,
        "road" -> serAR(sender)
      )

      case City.SystemShuttingDown(error) => Json.obj(
        "event"     -> "systemShuttingDown",
        "callback"  -> callback,
        "correctly" -> error.isEmpty,
        "error"   -> error
    )

      case x =>
        throw new IllegalArgumentException(s"Unknown Event object $x")
    })
}



object ActorRefSerializer {
  def serAR(ar: ActorRef): String =
    Serialization.serializedActorPath(ar).split('#')(0)
}



/** A generic request by the client. */
sealed trait JsonRequest


/**
 * A request to get the contents of the recipient.
 */
object GetContainedRequest extends JsonRequest {
  /**
   * @return if the input is of this type, the callback string
   */
  def unapply(js: JsonInbound): Option[String] =
    ((js.json \ "request").asOpt[String], (js.json \ "callback").asOpt[String]) match {
      case (Some("getContained"), Some(callback)) => Some(callback)
      case _ => None
    }

  def response(container: ActorRef, callback: String, info: JsValue) =
    SendJson(Json.obj(
      "event"     -> "containerResult",
      "container" -> serAR(container),
      "callback"  -> callback,
      "contained" -> info
    ))

  def response(container: ActorRef, callback: String, things: Seq[RoadThing]): SendJson =
    response(container, callback, new JsArray(things.map(JsonConversions.thingToJson)))
}

/**
 * A request to subscribe to the events published by the recipient.
 */
object SubscribeRequest extends JsonRequest {
  /**
   * @return if the input is of this type, the callback string and the client IP
   */
  def unapply(js: JsonInbound): Option[(String, String)] =
    ((js.json \ "request").asOpt[String], (js.json \ "callback").asOpt[String]) match {
      case (Some("subscribe"), Some(callback)) => Some(callback, js.remoteAddress)
      case _ => None
    }

  def response(callback: String, eventBus: ActorRef) =
    SendJson(Json.obj(
      "event"        -> "subscribeConfirmation",
      "callback"     -> callback,
      "subscribedTo" -> serAR(eventBus)))
}

/**
 * A request to unsubscribe from the events published by the recipient.
 */
object UnsubscribeRequest extends JsonRequest {
  /**
    * @return if the input is of this type, the callback string
   */
  def unapply(js: JsonInbound): Option[String] =
    ((js.json \ "request").asOpt[String], (js.json \ "callback").asOpt[String]) match {
      case (Some("unsubscribe"), Some(callback)) => Some(callback)
      case _ => None
    }

  def response(callback: String, eventBus: ActorRef) =
    SendJson(Json.obj(
      "event"            -> "unsubscribeConfirmation",
      "callback"         -> callback,
      "unsubscribedFrom" -> serAR(eventBus)))
}


/**
 * A request to jam the recipient.
 */
object SetJamRequest extends JsonRequest {
  /**
   * Matches requests to set jams.
   *
   * Parameters:
   *     request == "setJam"
   *     vehSlowFact: Int    (slowing factor for vehicles, optional, defaults to 1)
   *     pedSlowFact: Int    (slowing factor for pedestrians, optional, defaults to 1)
   *     duration:    Long   (duration of the jam, in milliseconds)
   *
   * @return if the input is of this type, the callback string,
   *         the car and pedestrian relative slowdown factor and the duration in millis
   */
  def unapply(js: JsonInbound): Option[(Int, Int, Millis)] =
    ( (js.json \ "request").asOpt[String],
      (js.json \ "vehSlowFact").asOpt[Int],
      (js.json \ "pedSlowFact").asOpt[Int],
      (js.json \ "duration").asOpt[Long]) match {

      case (Some("setJam"), vo, po, Some(d)) =>
        Some(vo.getOrElse(1), po.getOrElse(1), new Millis(d))

      case _ => None
    }
}

/**
 * A request to end the jam of the recipient.
 */
object UnsetJamRequest extends JsonRequest {
  def unapply(js: JsonInbound): Option[Unit] =
    (js.json \ "request").asOpt[String] match {
      case Some("setJam") => Some(Unit)
      case _ => None
    }
}

/**
 * A request to shut down the recipient.
 */
object ShutdownRequest extends JsonRequest {
  /**
   * @return if the input is of this type, Unit
   */
  def unapply(js: JsonInbound): Option[Unit] =
    (js.json \ "request").asOpt[String] match {
      case Some("shutdown") => Some(Unit)
      case _ => None
    }
}

/**
 * A request to get the current time.
 */
object GetTimeRequest extends JsonRequest {
  /**
   * @return if the input is of this type, the callback string
   */
  def unapply(js: JsonInbound): Option[String] =
    ((js.json \ "request").asOpt[String], (js.json \ "callback").asOpt[String]) match {
      case (Some("getTime"), Some(callback)) => Some(callback)
      case _ => None
    }

  def response(callback: String, now: Millis, dayDuration: Millis) =
    SendJson(Json.obj(
      "event"        -> "time",
      "callback"     -> callback,
      "now"          -> now,
      "dayDuration"  -> dayDuration))
}
