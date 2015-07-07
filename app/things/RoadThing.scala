package things

import java.io.ObjectInputStream

import actors.infra.position.ConnectionPosition
import actors.infra.road.Road.RoutableId

/**
 * Superclass of all things that move in roads and crossings.
 * Updates the reference counter (through [[ReferenceCounting]]
 * when it is created, deserialized or finalized.
 * @param idp identifier (used by the reference counter)
 */
abstract class RoadThing(protected val idp: String)
  extends ReferenceCounting with Serializable {

  @transient private var constructorCalled: Boolean = false
  @transient private var readObjectCalled: Boolean = false

  constructorCalled = true
  assert(!readObjectCalled)
  this.created(idp)

  /**
   * Called when an object is deserialized, used to update the reference counter
   * http://stackoverflow.com/a/7151833
   */
  private def readObject(in: ObjectInputStream) {
    in.defaultReadObject()
    readObjectCalled = true
    assert(!constructorCalled)
    this.created(idp)
  }

  override def finalize() {
    this.finalized(idp)
    super.finalize()
  }

  def id: String
}

trait HasNextDestination {
  this: RoadThing =>
  def destination: RoutableId
}

trait HasNextTurn {
  this: RoadThing =>
  def nextTurn(): (HasNextTurn, ConnectionPosition)
}
