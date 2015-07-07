package actors.infra

import actors.infra.ReferenceCounter.{CheckEntity, EntityLost}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import controllers.Timing.Millis
import things.{Created, Finalized}

import scala.collection.mutable

object ReferenceCounter {
  def props(city: ActorRef, timeout: Millis): Props = {
    Props(new ReferenceCounter(city, timeout))
  }

  sealed case class EntityLost private[ReferenceCounter] (id: String)
  private case class CheckEntity(id: String)
}

/**
 * Keeps count of the number of references to each [[things.RoadThing]] currently existing.
 * Warns the [[City]] if something vanishes.
 * @param city reference to the city
 * @param timeout timeout before sending the warning
 */
class ReferenceCounter private (city: ActorRef, timeout: Millis) extends Actor with ActorLogging {
  import context._

  val counter = mutable.Map[String, Int]().withDefaultValue(0)

  /**
   * `receive` function. Receives [[things.Created]] and [[things.Finalized]] notifications.
   */
  def receive = {
    case Created(id) =>
      counter(id) = counter(id) + 1

    case Finalized(id) =>
      counter(id) = counter(id) - 1
      if (counter(id) <= 0) {
        system.scheduler.scheduleOnce(timeout.fd, self, CheckEntity(id))

        if (counter(id) < 0)
          log.warning(s"counter($id) = ${counter(id)} < 0")
      }

    case CheckEntity(id) =>
      if (counter(id) <= 0)
        city ! EntityLost(id)
  }
}
