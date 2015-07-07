package things

import akka.actor.ActorRef
import akka.routing.ConsistentHashingRouter.ConsistentHashable

import scala.concurrent.Promise

/**
 * Notification that an entity has been created.
 * @param id identifier of this entity
 */
sealed case class Created(id: String) extends ConsistentHashable {
  def consistentHashKey = id
}

/**
 * Notification that an entity has been finalized.
 * @param id identifier of this entity
 */
sealed case class Finalized(id: String) extends ConsistentHashable {
  def consistentHashKey = id
}

object ReferenceCounting {
  val referenceCounterPromise = Promise[ActorRef]()
  lazy val referenceCounter = referenceCounterPromise.future.value.get.get
}

trait ReferenceCounting {
  /**
   * Sends a [[Created]] message to the [[actors.infra.ReferenceCounter]].
   * @param id identifier of the entity created
   */
  protected def created (id: String) {
    ReferenceCounting.referenceCounter ! Created(id)
  }

  /**
   * Sends a [[Finalized]] message to the [[actors.infra.ReferenceCounter]].
   * @param id identifier of the entity finalized
   */
  protected def finalized(id: String) {
    ReferenceCounting.referenceCounter ! Finalized(id)
  }
}

