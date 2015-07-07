package actors.infra.road

import actors.infra.Area.Shutdown
import actors.infra.road.Building.{PersonArriving, PersonFromBuilding, TimeToLeave}
import actors.notifications.EventBus.ThingEnteredStructure
import akka.actor._
import controllers.Timing
import controllers.Timing.{DayTime, Time, nextDayTime, waitUntil}
import controllers.json.GetContainedRequest
import things.Person

object Building {
  def props(eventBus: ActorRef) = Props(new Building(eventBus))

  sealed case class PersonArriving (person: Person)
  sealed case class PersonFromBuilding (person: Person, timestamp: Time)

  private case object TimeToLeave
}


/**
 * Handles a building. Pedestrians are sent here by the road; they are sent back to
 * the road when their schedule tells them to go elsewhere.
 * @param eventBus the [[actors.notifications.EventBus]] for the area
 */
class Building private (eventBus: ActorRef) extends Actor with ActorLogging {
  import context._

  var people = List[(Person, Time)]()

  /**
   * @return the time when the next person needs to exit
   */
  def nextTime: Time = people.minBy{_._2}._2

  var nextAlarm: Option[Cancellable] = None

  /**
   * `receive` function. Handles incoming pedestrians.
   */
  override def receive: Receive = {
    case PersonArriving(p) =>
      val deadline: DayTime = p.schedule.nextTime
      val exit: Time = nextDayTime(deadline)
      people = people :+ (p, exit)
      log.info(s"person $p arrived at ${Timing.timeOfDay} and will leave at $deadline")
      eventBus ! ThingEnteredStructure(p, "build")

      nextAlarm.foreach(_.cancel())
      nextAlarm = Some(system.scheduler.scheduleOnce(waitUntil(nextTime).fd, self, TimeToLeave))

    case TimeToLeave =>
      val (toExit, remaining) = people partition {case (_, exit) => exit <= Time.now}

      for ((p, exit) <- toExit) {
        log.info(s"person $p exiting at ${Timing.timeOfDay}")
        parent ! PersonFromBuilding(p, exit)
      }

      people = remaining

      if (remaining.nonEmpty) {
        nextAlarm = Some(system.scheduler.scheduleOnce(waitUntil(nextTime).fd, self, TimeToLeave))
      } else {
        nextAlarm = None
      }

    case GetContainedRequest(cbk) =>
      sender ! GetContainedRequest.response(self, cbk, people.map(_._1))

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
