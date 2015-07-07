package actors.infra.road

import actors.infra.Area.Shutdown
import actors.infra.Container.{ContainedInfo, ContainedReceived}
import actors.infra._
import actors.infra.cross.CrossingBase.LaneSelector
import actors.infra.position.ConnectionPosition
import actors.infra.road.Lane.{ExitingVehicle, StatsTick, VehicleTransited}
import actors.infra.road.Road.Jam
import actors.infra.road.RoadLoadManager.LaneTime
import actors.notifications.EventBus.{ThingEnteredStructure, ThingExitedArea}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.typesafe.config.ConfigFactory
import controllers.Socket.{SendJson, JsonInbound}
import controllers.Timing._
import controllers.json.ActorRefSerializer._
import play.api.libs.json.{JsString, Json}
import things.Bus.BusCategory
import things.Vehicle

import scala.collection.mutable
import scala.language.implicitConversions

object Lane {
  def getProps(index: Int, selector: LaneSelector, length: Millis,
               capacity: Int, to: ActorRef, road: ActorRef,
               loadManager: ActorRef, eventBus: ActorRef) =
    Props(new Lane(index, selector, length, capacity, to, road, loadManager, eventBus))

  private val statsEvery = new Millis(ConfigFactory.load.getMilliseconds("infra.global.lane.statsEvery"))

  private case object StatsTick

  private case class VehicleTransited(vehicle: Vehicle,
                                      nextTurn: ConnectionPosition,
                                      enterTime: Time,
                                      expected: Time)
  sealed case class ExitingVehicle(vehicle: Vehicle,
                                   enterTime: Time,
                                   nextTurn: ConnectionPosition)
}

/**
 * Handles a single lane for vehicles.
 * @param index the index of the lane
 * @param selector the [[actors.infra.cross.CrossingBase.LaneSelector]] of the lane
 * @param length the time taken to go through the lane
 * @param capacity the capacity of the lane
 * @param to the destination
 * @param road the roads that owns the lane
 * @param loadManager the [[RoadLoadManager]] of the road
 * @param eventBus the [[actors.notifications.EventBus]] for the area
 */
sealed class Lane private (index: Int, selector: LaneSelector, length: Millis,
                           capacity: Int, to: ActorRef, road: ActorRef,
                           loadManager: ActorRef, eventBus: ActorRef
                            ) extends Actor with ActorLogging {
  import context._

  /**
   * Current slowdown factor due to jams.
   */
  var jamFactor: Int = 1
  def timeLength: Millis = length * jamFactor

  val info = Json.obj(
    "isBusReserved" -> (selector.category == Some(BusCategory))
  )

  parent ! ContainedInfo(info)
  parent ! ContainedReceived

  var transitingVehiclesUtilization = 0
  val exitingQueue = mutable.Queue[ExitingVehicle]()
  def spaceUtilization: Int = transitingVehiclesUtilization +
    exitingQueue.map(_.vehicle.spacesUsed).sum
  def isFull: Boolean = spaceUtilization >= capacity

  var vehiclesExitedThisPeriod: Int = 0
  var timeTakenThisPeriod: Millis = Millis.Zero
  var averageTimeToExit: Millis = Millis.Zero

  def metric: Millis = timeLength + averageTimeToExit * exitingQueue.size

  var guard: Boolean = true
  var previousGuard: Boolean = true

  /**
   * Checks if the lane needs to send a [[CanSendMore]] to the road.
   */
  def sendControlMessages(t: Time): Unit = {
    if (! previousGuard && ! isFull) {
      road ! CanSendMore(t)
      previousGuard = true
    }

    loadManager ! LaneTime(index, metric)
  }

  /**
   * Checks if the lane may forward a vehicle.
   */
  def checkExit(t: Time): Unit = {
    if (guard && exitingQueue.nonEmpty) {
      val qr = exitingQueue.dequeue()
      guard = false
      vehiclesExitedThisPeriod += 1
      timeTakenThisPeriod += (t - qr.enterTime)

      log.info(s"sending veh ${qr.vehicle.id}")
      to ! VehicleFromLane(qr.vehicle, qr.nextTurn, t)
    }
  }

  system.scheduler.schedule(initialDelay = Lane.statsEvery.fd,
                            interval     = Lane.statsEvery.fd,
                            receiver     = self,
                            message      = StatsTick)

  /**
   * `receive` function. Handles incoming traffic.
   * Every `StatsTick` computes statistics about its recent performance.
   */
  override def receive: Receive = {
    case VehicleToLane(veh, nt, t) =>
      if (isFull) {
        throw new Error(s"${serAR(self)}: Received vehicle while full, dropping $veh")
      } else {
        eventBus ! ThingEnteredStructure(veh, "lane", Some(timeLength))
        log.info(s"received veh ${veh.id} going $nt")
        
        transitingVehiclesUtilization += veh.spacesUsed
        val (end, wait) = waitFor(timeLength, t)
        system.scheduler.scheduleOnce(wait.fd, self, VehicleTransited(veh, nt, t, end))

        previousGuard = false
        sendControlMessages(t)
      }

    case VehicleTransited(veh, nt, et, exp) =>
      transitingVehiclesUtilization -= veh.spacesUsed
      exitingQueue += ExitingVehicle(veh, et, nt)
      checkExit(exp)

    case CanSendMore(t) =>
      guard = true
      checkExit(t)
      sendControlMessages(t)

    case StatsTick =>
      if (vehiclesExitedThisPeriod > 0) {
        averageTimeToExit = (timeTakenThisPeriod / vehiclesExitedThisPeriod) - length
      } else if (!guard) {
        averageTimeToExit = Lane.statsEvery
      } else {
        averageTimeToExit = Millis.Zero
      }
      vehiclesExitedThisPeriod = 0
      timeTakenThisPeriod = Millis.Zero
      loadManager ! LaneTime(index, metric)

    case Jam(fact) =>
      jamFactor = fact
      loadManager ! LaneTime(index, metric)

    case e: ThingExitedArea =>
      eventBus ! e

    case Shutdown =>
      become(down)
      parent ! ContainedReceived

    case JsonInbound(_, _) =>
      sender ! SendJson(JsString(s"${serAR(self)}\n" +
        s"transitingVehiclesUtilization = $transitingVehiclesUtilization,\n" +
        s"exitingQueue=$exitingQueue,\n" +
        s"guard=$guard,\n" +
        s"previousGuard=$previousGuard)\n"))
  }



  /**
   * `receive` function for the `down` state. Ignores all messages.
   */
  def down: Receive = {
    case _ =>
  }
}

