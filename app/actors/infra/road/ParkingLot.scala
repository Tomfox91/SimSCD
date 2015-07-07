package actors.infra.road

import actors.infra.Area.Shutdown
import actors.infra.CanSendMore
import actors.infra.road.ParkingLot.{CarArriving, CarFromParkingLot, PersonFromParkingLot, PersonReturning}
import actors.infra.road.Road.{NearestFreeParking, RoadId}
import actors.infra.road.RoadRoutingAgent.{PublishAdditionalRoutingInformation, UnPublishAdditionalRoutingInformation}
import actors.notifications.EventBus.ThingEnteredStructure
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import controllers.Timing.Time
import controllers.json.GetContainedRequest
import things.{Car, ParkedCar, ParkedCarSpec, PersonWithCar}

import scala.collection.mutable


object ParkingLot {
  def props(road: RoadId, routingAgent: ActorRef,
            capacity: Int = Int.MaxValue, initialCars: Seq[ParkedCarSpec],
            eventBus: ActorRef): Props =
    Props(new ParkingLot(road, routingAgent, capacity, initialCars, eventBus))

  sealed case class CarArriving(vehicle: Car, timestamp: Time)
  sealed case class PersonReturning(person: PersonWithCar, timestamp: Time)
  sealed case class PersonFromParkingLot(person: PersonWithCar, timestamp: Time)
  sealed case class CarFromParkingLot(car: Car, timestamp: Time)
}


/**
 * Handles a parking lot. Cars and pedestrians are sent here by the road.
 * @param road the id of the road that owns this parking lot
 * @param routingAgent the [[RoadRoutingAgent]] to inform when the parking is full (or not full)
 * @param capacity the capacity of the parking lot
 * @param initialCars specification for the initially parked cars
 * @param eventBus the [[actors.notifications.EventBus]] for the area
 */
class ParkingLot (road: RoadId, routingAgent: ActorRef,
                  capacity: Int, initialCars: Seq[ParkedCarSpec], eventBus: ActorRef
                   ) extends Actor with ActorLogging {
  import context._

  val vehiclesIn: mutable.Set[ParkedCar] = initialCars.map(_.makeParkedCar).to

  def isFree: Boolean = vehiclesIn.size < capacity
  var wasFree: Boolean = false

  override def preStart() {
    checkFree()
  }

  /**
   * Publishes or removes the [[Road.NearestFreeParking]] information, depending
   * on the current free/full status.
   */
  def checkFree() {
    if (isFree && !wasFree) {
      routingAgent ! PublishAdditionalRoutingInformation(NearestFreeParking)
      wasFree = true
    } else if (!isFree && wasFree) {
      routingAgent ! UnPublishAdditionalRoutingInformation(NearestFreeParking)
      wasFree = false
    }
  }

  var exitGuard: Boolean = true
  var exiting = mutable.Queue[Car]()

  /**
   * Checks the guard on the exit queue and, if it can, sends a car to the road.
   */
  def checkExit(t: Time) {
    if (exitGuard && exiting.nonEmpty) {
      parent ! CarFromParkingLot(exiting.dequeue(), t)
      exitGuard = false
    }
  }


  /**
   * `receive` function. Handles cars incoming and pedestrians incoming (to reclaim their car).
   */
  override def receive: Receive = {
    case CarArriving(v, t) =>
      if (isFree) {
        vehiclesIn += ParkedCar(v.id, v.ownerId)
        checkFree()
        log.info(s"left car ${v.id}")
        eventBus ! ThingEnteredStructure(v, "park")
        parent ! PersonFromParkingLot(v.person.withCarParked(in = road), t)

      } else {
        log.info(s"bounced car ${v.id}")
        exiting.enqueue(v.withDestinationOverride(NearestFreeParking))
        checkExit(t)
      }

    case PersonReturning(p, t) =>
      vehiclesIn -= ParkedCar(p.carId, p.id)
      checkFree()
      log.info(s"exiting car ${p.carId}")
      eventBus ! ThingEnteredStructure(p, "park")
      exiting.enqueue(Car(p.withoutCarParked))
      checkExit(t)

    case CanSendMore(t) =>
      exitGuard = true
      checkExit(t)

    case GetContainedRequest(cbk) =>
      sender ! GetContainedRequest.response(self, cbk, vehiclesIn.toSeq)

    case Shutdown =>
      become(down)
  }

  /**
   * `receive` function for the `down` state. Ignores all messages.
   */
  def down: Receive = {
    case _ =>
  }
}
