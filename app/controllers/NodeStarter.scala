package controllers

import actors.infra.Area.Shutdown
import akka.actor._
import com.typesafe.config.ConfigFactory
import controllers.RemoteAgent.{CreateReferenceCounterRelay, ReferenceCounterRelayCreated, Watch}
import things.ReferenceCounting

/**
 * Main class to start remote nodes. Creates a local actor system and a [[RemoteAgent]].
 */
object NodeStarter extends App {
  if (args.length != 1) {
    sys.error("Expecting exactly 1 argument")
    sys.exit(-1)
  }

  Timing.init()

  val nodeName = args(0)
  val config = ConfigFactory.load
  val nodeSystem = ActorSystem(name = nodeName,
    config = config.getConfig(s"node.$nodeName").withFallback(config))

  nodeSystem.actorOf(Props[RemoteAgent], "remoteAgent")
}

object RemoteAgent {
  sealed case class Watch(actor: ActorRef)

  sealed case class CreateReferenceCounterRelay(referenceCounter: ActorRef)
  case object ReferenceCounterRelayCreated
}

/**
 * Actor to receive commands from the remote nodes, e.g. [[actors.infra.Area.Shutdown]]
 */
class RemoteAgent extends Actor with ActorLogging {
  import context._

  def receive = {
    case CreateReferenceCounterRelay(rc) =>
      val relay = actorOf(ReferenceCounterRelay.props(rc), name = "referenceCounterRelay")
      ReferenceCounting.referenceCounterPromise.success(relay)
      sender ! ReferenceCounterRelayCreated

    case Watch(actor) =>
      watch(sender)
      watch(actor)

    case Terminated(_) =>
      self ! Shutdown

    case Shutdown =>
      log.info(s"Shutting down ActorSystem $system")
      children foreach system.stop
      system.shutdown()
  }
}

object ReferenceCounterRelay {
  def props(referenceCounter: ActorRef): Props =
    Props(new ReferenceCounterRelay(referenceCounter))
}

/**
 * Relays reference counting events to the main [[actors.infra.ReferenceCounter]].
 * @param referenceCounter a reference to the main [[actors.infra.ReferenceCounter]]
 */
class ReferenceCounterRelay private (referenceCounter: ActorRef) extends Actor {
  def receive = {
    case m => referenceCounter ! m
  }
}