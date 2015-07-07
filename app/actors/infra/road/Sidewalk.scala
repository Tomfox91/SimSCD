package actors.infra.road

import actors.infra.Area.Shutdown
import actors.infra._
import actors.infra.position.PedestrianConnectionPosition
import actors.infra.road.Road.Jam
import actors.infra.road.Sidewalk.PedestrianTransited
import actors.notifications.EventBus.{ThingEnteredStructure, ThingExitedArea}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import controllers.Timing._
import things.Pedestrian

object Sidewalk {
  def getProps(length: Millis, to: ActorRef, eventBus: ActorRef) =
    Props(new Sidewalk(length, to, eventBus))

  private case class PedestrianTransited(pedestrian: Pedestrian,
                                         nextTurn: PedestrianConnectionPosition,
                                         expected: Time)
}

/**
 * Handles a one-way sidewalk for pedestrians.
 * @param length the time taken to go through the lane
 * @param to the destination
 * @param eventBus the [[actors.notifications.EventBus]] for the area
 */
sealed class Sidewalk private (length: Millis, to: ActorRef, eventBus: ActorRef
                                ) extends Actor with ActorLogging {
  import context._

  /**
   * Current slowdown factor due to jams.
   */
  var jamFactor: Int = 1
  def timeLength: Millis = length * jamFactor

  /**
   * `receive` function. Handles incoming pedestrians.
   */
  override def receive: Receive = {
    case PedestrianToSidewalk(ped, nextTurn, t, shortcut) =>
      log.info(s"received ped ${ped.id} to $nextTurn" +
        (if (shortcut) " with shortcut" else ""))

      eventBus ! ThingEnteredStructure(ped, "sidewalk", Some(
        if (shortcut) new Millis(1) else timeLength))

      val (end, wait) = waitFor(if (shortcut) new Millis(1) else timeLength, t)
      system.scheduler.scheduleOnce(wait.fd, self, PedestrianTransited(ped, nextTurn, end))

    case PedestrianTransited(ped, nt, exp) =>
      to ! PedestrianFromSidewalk(ped, nt, exp)

    case Jam(fact) =>
      jamFactor = fact

    case e: ThingExitedArea =>
      eventBus ! e

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

