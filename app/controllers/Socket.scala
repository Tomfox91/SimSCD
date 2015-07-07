package controllers

import actors.System
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import controllers.json.ActorRefSerializer.serAR
import play.api.Logger
import play.api.libs.iteratee._
import play.api.libs.json.JsValue
import play.api.mvc.WebSocket

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.util.Success

/**
 * Handles [[controllers.Socket.SocketActor]] creation.
 */
object Socket {

  sealed case class SendJson(json: JsValue)
  sealed case class JsonInbound(json: JsValue, remoteAddress: String)

  object SocketActor {
    def props(out: Concurrent.Channel[JsValue]): Props = Props(new SocketActor(out))
  }

  /**
   * Handles communication between a WebSocket channel and the actor system.
   * @param out the outbound WebSocket channel
   */
  class SocketActor private (val out: Concurrent.Channel[JsValue]) extends Actor {
    def receive = {
      case ji: JsonInbound =>
        (ji.json \ "dest").asOpt[String] match {
          case (Some(to)) =>
            System.infraSystem.actorSelection(to) ! ji

          case x =>
            Logger.error(s"SocketActor ${serAR(self)} received illegal object $x")
        }

      case SendJson(js) =>
        out.push(js)
    }
  }

  /**
   * Handles a new WebSocket connection. Creates a new [[SocketActor]] to handle it.
   */
  def ws() = WebSocket.using[JsValue] {
    request =>

      val actorPromise = Promise[ActorRef]()

      val out: Enumerator[JsValue] = Concurrent.unicast(
        onStart = (ch: Concurrent.Channel[JsValue]) => {
          actorPromise.success(System.infraSystem.actorOf(SocketActor.props(ch)))
        })

      def in: Iteratee[JsValue, Unit] = {
        def eof() = {
          actorPromise.future.foreach(_ ! PoisonPill)
          Done[JsValue, Unit](Unit, Input.EOF)
        }

        def ck(in: Input[JsValue]): Iteratee[JsValue, Unit] =
          actorPromise.future.value match {
            case (Some(Success(act))) =>
              okCont(act)(in)
            case _ => in match {
              case Input.EOF | Input.Empty =>
                eof()
              case _: Input.El[JsValue] =>
                Cont(ck)
            }
          }

        def okCont(socketActor: ActorRef) = {
          def iterate(in: Input[JsValue]): Iteratee[JsValue, Unit] = in match {
            case Input.EOF | Input.Empty =>
              eof()
            case Input.El(js) =>
              socketActor ! JsonInbound(js, request.remoteAddress)
              Cont(iterate)
          }
          iterate _
        }

        Cont(ck)
      }

      (in, out)
  }
}
