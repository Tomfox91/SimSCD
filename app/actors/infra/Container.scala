package actors.infra

import actors.infra.Container._
import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor._
import controllers.json.ActorRefSerializer.serAR
import controllers.json.GetContainedRequest
import play.api.libs.json.{JsObject, JsString, Json}

import scala.collection.mutable

object Container {

  /**
   * Prepares [[akka.actor.Props]] for a Container from a range and a function.
   * @param range the range of children to create
   * @param createChildProp the function to produce the [[akka.actor.Props]] of each child
   * @param createdMessage message to send to the parent when all children are ready
   */
  def propsFromRange(range: Range,
                     createChildProp: Int => Props,
                     createdMessage: Serializable): Props = {
    val np = range map (i => (i.toString, i, createChildProp(i)))

    Props(new Container(np, createdMessage))
  }


  /**
   * Prepares [[akka.actor.Props]] for a Container.
   * @tparam RefT type of the identifier of the children
   * @param namesAndProps sequence of properties of the desired children.
   *                      For each one, a name, a reference and its [[akka.actor.Props]].
   * @param createdMessage message to send to the parent when all children are ready
   */
  def props[RefT](namesAndProps: Seq[(String, RefT, Props)],
                  createdMessage: Serializable) =
    Props(new Container(namesAndProps, createdMessage))

  sealed case class ContainerPopulated[RefT](message: Serializable, containedMap: Map[RefT, ActorRef])

  case object ContainedReceived
  sealed case class ContainedInfo(info: JsObject)
  sealed case class SendToContained(mess: Serializable, completedMessage: Serializable)

  case object WatchChildren
  sealed case class ChildDied private[Container] (child: ActorRef)
}

/**
 * Creates and maintains multiple homogeneous actors.
 * @tparam RefT type of the identifier of the children
 * @param propSeq sequence of properties of the desired children.
 *                For each one, a name, a reference and its [[akka.actor.Props]].
 * @param createdMessage message to send to the parent when all children are ready
 */
sealed class Container[RefT] private (propSeq: Seq[(String, RefT, Props)],
                                      createdMessage: Serializable) extends Actor with Stash {
  import context._

  var supStrategy = AllForOneStrategy() {case _ => Escalate}
  override def supervisorStrategy = supStrategy

  val contained: Map[RefT, ActorRef] =
    propSeq.map{case (name, ref, props) => (ref, actorOf(props, name))}.toMap

  def receive = waitingForCreation

  val containedInfo = mutable.HashMap[ActorRef, JsObject]()

  /**
   * Transition function to the initial `waitingForCreation` state.
   * @return `receive` function for this state.
   *         Waits for [[Container.ContainedReceived]] from all children and notifies the parent.
   */
  def waitingForCreation: Receive = {
    var remaining = propSeq.length

    {
      case ContainedReceived =>
        remaining -= 1

        if (remaining == 0) {
          parent ! ContainerPopulated(createdMessage, contained)
          unstashAll()

          become(normal)
        }

      case _ => stash()
    }
  }

  /**
   * Transition function to the `normal` state.
   * @return `receive` function for this state.
   */
  def normal: Receive = {
    case SendToContained(mess, completedMess) =>
      children foreach {_ ! mess}
      become(waitingForReceived(completedMess), discardOld = false)

    case ContainedInfo(inf) =>
      containedInfo += ((sender, inf))

    case GetContainedRequest(callback) =>
      val sortedInfo: Seq[JsObject] =
        containedInfo.toSeq.map{case (a, i) =>
          (a.path.toString, i + ("name" -> JsString(serAR(a))))
        }.sortBy(_._1).map(_._2)

      sender ! GetContainedRequest.response(self, callback, Json.toJson(sortedInfo))

    case WatchChildren =>
      supStrategy = AllForOneStrategy() {case _ => Stop}
      contained.values foreach watch

    case Terminated(actor) =>
      parent ! ChildDied(actor)
  }

  /**
   * Transition function to the `waitingForReceived` state.
   * @param completedMess the message to send to the parent when all children acknowledged
   * @return `receive` function for this state.
   *         Waits for [[Container.ContainedReceived]] from all children and notifies the parent.
   */
  def waitingForReceived(completedMess: Serializable): Receive = {
    var remaining = propSeq.length

    {
      case ContainedReceived =>
        remaining -= 1

        if (remaining == 0) {
          parent ! completedMess
          unstashAll()
          unbecome()
        }

      case _ => stash()
    }
  }
}
