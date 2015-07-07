package actors.infra.road

import actors.infra.Area.Shutdown
import actors.infra.road.RoadLoadManager.LaneTime
import actors.infra.road.RoadRoutingAgent.RoadQueueMetric
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.typesafe.config.ConfigFactory
import controllers.Timing._

import scala.collection.mutable

object RoadLoadManager {
  def props(roadRoutingAgent: ActorRef, initialMetric: Millis, laneCount: Int) =
    Props(new RoadLoadManager(roadRoutingAgent, initialMetric, laneCount))

  private val communicationThreshold =
    new Millis(ConfigFactory.load.getMilliseconds("infra.global.roadLoadManager.communicationThreshold"))

  sealed case class LaneTime(lane: Int, time: Millis)
}

/**
 * Receives [[actors.infra.road.RoadLoadManager.LaneTime]] from [[Lane]]s,
 * computes averages and sends updated metrics to the road's [[RoadRoutingAgent]].
 * @param roadRoutingAgent the road's [[RoadRoutingAgent]]
 * @param initialMetric the initial metric
 * @param laneCount the number of lanes
 */
sealed class RoadLoadManager private (roadRoutingAgent: ActorRef, initialMetric: Millis,
                                      laneCount: Int) extends Actor with ActorLogging {
  import context._
  var lastMetric: Millis = initialMetric

  val metrics: mutable.Seq[Millis] =
    mutable.Seq() ++ (for (i <- 0 to laneCount-1) yield initialMetric)
  def metric: Millis = metrics.reduce(_ + _) / laneCount

  def sendRQM() {
    if ((lastMetric |-| metric) > RoadLoadManager.communicationThreshold) {
      log.info(s"sending metric $metric")
      roadRoutingAgent ! RoadQueueMetric(metric)
      lastMetric = metric
    }
  }

  def receive = {
    case LaneTime(lane, time) =>
      metrics(lane) = time
      sendRQM()

    case Shutdown =>
      become(down)
  }

  def down: Receive = {
    case _ =>
  }
}
