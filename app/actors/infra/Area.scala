package actors.infra

import java.util

import actors.infra.Area._
import actors.infra.Container.{ContainedInfo, ContainedReceived, ContainerPopulated, SendToContained}
import actors.infra.cross.CrossingBase
import actors.infra.road.Road
import actors.notifications.EventBus
import akka.actor.SupervisorStrategy.Escalate
import akka.actor.{Actor, AllForOneStrategy, Props}
import controllers.RemoteAgent.Watch
import play.api.Configuration
import play.api.libs.json.Json

import scala.collection.convert.Wrappers.JListWrapper

object Area {
  /**
   * Prepares the parameters for an Area from the configuration
   * @param areaId identifier of the area
   * @param config configuration for this Area
   * @param infraConfig global configuration for the infrastructure
   * @return [[akka.actor.Props]] Props for creating this actor
   */
  def props(areaId: Int, config: Configuration, infraConfig: Configuration): Props = {
    val xy: util.List[Integer] =
      infraConfig.getConfigList("city.area").get.get(areaId).getIntList("pos").get

    assert(xy.size() == 2)
    Props(new Area(xy.get(0), xy.get(1), areaId, config, infraConfig))
  }

  case object CreateCrossings
  case object CreateRoads
  case object FinishCrossings
  case object StartRouting
  case object StartThings
  case object Shutdown

  private case object AllCrossesReady
  private case object AllRoadsReady
  private case object AllCrossesFinished
  private case object AllRoutingReady
  private case object AllThingsStarted
  private case object AllCrossesDown
  private case object AllRoadsDown
}

/**
 * Contains roads and crossings. Helps with their startup.
 * @param x x-axis position
 * @param y y-axis position
 * @param areaId identifier of the area
 * @param config configuration for this Area
 * @param infraConfig global configuration for the infrastructure
 */
sealed class Area private (x: Int, y: Int, areaId: Int, config: Configuration,
                           infraConfig: Configuration) extends Actor {
  import context._

  override val supervisorStrategy = AllForOneStrategy() {case _ => Escalate}

  val eventBus = actorOf(Props[EventBus], "eventBus")

  val crossesConfig = JListWrapper(config.getConfigList("cross").get)
  val roadsConfig = JListWrapper(config.getConfigList("road").get)

  val info = Json.obj(
    "pos"  -> Json.obj("x" -> x, "y" -> y)
  )

  override def preStart() = {
    parent ! ContainedInfo(info)
    parent ! ContainedReceived
  }

  def receive = {
    case CreateCrossings =>
      actorOf(
          Container.propsFromRange(
            range = crossesConfig.indices,
            createChildProp = {i =>
              CrossingBase.getProps(configuration = infraConfig,
                                areaId = areaId,
                                crossId = i,
                                eventBus = eventBus)},
            createdMessage = AllCrossesReady)
        , "cross")

    case ContainerPopulated(AllCrossesReady, _) =>
    parent ! ContainedReceived

    case CreateRoads =>
      val namesAndProps = roadsConfig.zipWithIndex map {case (conf, rid) =>
        Road.getNamesAndProps(configuration = infraConfig,
                              areaNum = areaId,
                              roadNum = rid,
                              eventBus = eventBus)
      } reduce (_ ++ _)

      actorOf(
        Container.props(
          namesAndProps = namesAndProps,
          createdMessage = AllRoadsReady)
        , "road")

    case ContainerPopulated(AllRoadsReady, _) =>
      parent ! ContainedReceived

    case FinishCrossings =>
      child("cross").get ! SendToContained(CrossingBase.AllRoadsRegistered, AllCrossesFinished)
    case AllCrossesFinished =>
      parent ! ContainedReceived

    case StartRouting =>
      child("road").get ! SendToContained(Road.StartRouting, AllRoutingReady)
    case AllRoutingReady =>
      parent ! ContainedReceived

    case StartThings =>
      actorSelection(system / "remoteAgent") ! Watch(parent)
      child("road").get ! SendToContained(Road.StartThings, AllThingsStarted)
    case AllThingsStarted =>
      parent ! ContainedReceived

    case Shutdown =>
      child("road").get  ! SendToContained(Shutdown, AllRoadsDown)
    case AllRoadsDown =>
      child("cross").get ! SendToContained(Shutdown, AllCrossesDown)
    case AllCrossesDown =>
      parent ! ContainedReceived
  }
}

