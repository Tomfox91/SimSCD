package actors.infra.cross

import actors.infra.Container.ContainedInfo
import actors.infra.cross.CrossingBase._
import actors.infra.cross.TrafficLightCrossing.{TurnEnd, ChangeLight}
import actors.infra.position
import actors.infra.position.ConnectionPosition
import actors.infra.position.TrajectoryCmp.Trajectory
import actors.notifications.EventBus.Event
import akka.actor.{ActorRef, Props}
import controllers.Timing
import controllers.Timing.{Time, Millis}
import play.api.libs.json.Json

import scala.collection.mutable
import scala.util.Random

object TrafficLightCrossing {
  /**
   * Prepares [[akka.actor.Props]] for a TrafficLightCrossing.
   * @param crossId id of this Crossing
   * @param x position on the x axis
   * @param y position on the y axis
   * @param laneSelectors for each [[position.ConnectionPosition]],
   *                      the sequence of lane descriptors [[CrossingBase.LaneSelector]] on that side
   * @param priorities priority of each side of the Crossing ([[position.ConnectionPosition]])
   * @param lightTiming a sequence of [[CrossingBase.TurnSpec]] that describe the turns of the traffic lights
   * @param eventBus reference to the [[actors.notifications.EventBus]] of this Area
   */
  def getProps(crossId: CrossingId, x: Double, y: Double,
               laneSelectors: Map[ConnectionPosition, Seq[LaneSelector]],
               transitTime: Millis, pedestrianCrossingTime: Millis,
               priorities: Map[ConnectionPosition, Int],
               lightTiming: Seq[TurnSpec],
               eventBus: ActorRef) =
  Props(new TrafficLightCrossing(
    crossId, x, y, laneSelectors, transitTime, pedestrianCrossingTime, lightTiming, priorities, eventBus))

  sealed case class ChangeLight(greenVehicles: Seq[ConnectionPosition],
                                greenPedestrians: Seq[ConnectionPosition]) extends Event

  private[TrafficLightCrossing] case class TurnEnd(exp: Time)
}

/**
 * Manages a crossing with traffic lights.
 * @param crossId id of this Crossing
 * @param x position on the x axis
 * @param y position on the y axis
 * @param transitTime time needed by a vehicle to go through the crossing
 * @param pedestrianCrossingTime time needed by a pedestrian to cross the road
 * @param laneSelectors for each [[position.ConnectionPosition]],
 *                      the sequence of lane descriptors [[CrossingBase.LaneSelector]] on that side
 * @param priorities priority of each side of the Crossing ([[position.ConnectionPosition]])
 * @param lightTiming a sequence of [[TurnSpec]] that describe the turns of the traffic lights
 * @param eventBus reference to the [[actors.notifications.EventBus]] of this Area
 */
sealed class TrafficLightCrossing private (crossId: CrossingId, x: Double, y: Double,
                                           laneSelectors: Map[ConnectionPosition, Seq[LaneSelector]],
                                           transitTime: Millis, pedestrianCrossingTime: Millis,
                                           lightTiming: Seq[TurnSpec],
                                           priorities: Map[ConnectionPosition, Int],
                                           eventBus: ActorRef)
  extends PriorityCrossing(crossId, x, y, laneSelectors, transitTime, pedestrianCrossingTime,
    priorities, eventBus) {

  import context._

  override protected def setInfo() {
    context.parent ! ContainedInfo(Json.obj(
      "pos"  -> Json.obj("x" -> x, "y" -> y),
      "type" -> "lights"
    ))
  }

  /**
   * Handles the cyclic lights schedule.
   */
  private object TurnQueue {
    private val turnQueue: mutable.Queue[TurnSpec] = mutable.Queue() ++ lightTiming
    private var vRed: Set[ConnectionPosition] = Set()
    private var pRed: Set[ConnectionPosition] = Set()
    def vehRedRoads = vRed
    def pedRedRoads = pRed

    def next(): Millis = {
      val next = turnQueue.dequeue()
      vRed = next.redVehicles
      pRed = next.redPedestrians
      setLights(greenVeh = next.greenVehicles.toSeq, greenPed = next.greenPedestrians.toSeq)
      turnQueue.enqueue(next)
      next.duration
    }

    private def setLights(greenVeh: Seq[ConnectionPosition], greenPed: Seq[ConnectionPosition]) {
      eventBus ! ChangeLight(greenVeh, greenPed)

      context.parent ! ContainedInfo(Json.obj(
        "pos"  -> Json.obj("x" -> x, "y" -> y),
        "type" -> "lights",
        "greenRoads"       -> greenVeh.map(_.toChar.toString),
        "greenPedestrians" -> greenPed.map(_.toChar.toString)
      ))
    }
  }

  /**
   * @return the appropriate [[GuardState]]
   */
  override protected def mkGuards = new LightsGuards(_, _, _)

  sealed protected class LightsGuards(currentVehicleTransits: mutable.Set[Trajectory],
                                      currentPedestriansWaiting: mutable.Map[ConnectionPosition, Boolean],
                                      currentPedestrianTransits: mutable.Map[ConnectionPosition, LaneId])
    extends PriorityGuards(currentVehicleTransits, currentPedestriansWaiting, currentPedestrianTransits) {

    override def vehicleRedRoads: Set[ConnectionPosition] = TurnQueue.vehRedRoads
    override def pedestrianRedRoads: Set[ConnectionPosition] = TurnQueue.pedRedRoads

    override def toString: String =
      s"currentVehicleTransits    = $currentVehicleTransits \n" +
        s"currentPedestriansWaiting = $currentPedestriansWaiting \n" +
        s"currentPedestrianTransits = $currentPedestrianTransits \n" +
        s"vehicleRedRoads           = $vehicleRedRoads \n" +
        s"pedestrianRedRoads        = $pedestrianRedRoads \n"
  }

  /**
   * @return the actual `receive` function for the `ready` state.
   *         Handles pedestrian and vehicle forwarding (through `super`) and light turn changes.
   */
  override protected def mkReadyReceive(crossingContext: CrossingContext, guards: GuardState,
                                        vh: VehicleHandler, ph: PedestrianHandler
                                         ): Receive = {

    val sup = super.mkReadyReceive(crossingContext, guards, vh, ph)

    {
      for (i <- 1 to Random.nextInt(lightTiming.size)) TurnQueue.next()
      val (end, wait) = Timing.waitFor(TurnQueue.next().randomPart)
      system.scheduler.scheduleOnce(wait.fd, self, TurnEnd(end))
    }

    sup orElse {
      case TurnEnd(exp) =>
        val (end, wait) = Timing.waitFor(TurnQueue.next(), exp)
        system.scheduler.scheduleOnce(wait.fd, self, TurnEnd(end))

        ph.tryForward(exp)
        vh.tryForward(exp)
    }
  }
}

