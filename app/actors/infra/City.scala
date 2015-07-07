package actors.infra

import actors.infra.City._
import actors.infra.Container.{ChildDied, ContainerPopulated, SendToContained, WatchChildren}
import actors.infra.ReferenceCounter.EntityLost
import actors.notifications.EventBus
import actors.notifications.EventBus.Event
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.routing.ConsistentHashingRouter
import controllers.RemoteAgent.{CreateReferenceCounterRelay, ReferenceCounterRelayCreated}
import controllers.Timing
import controllers.Timing.Millis
import controllers.json.{GetTimeRequest, ShutdownRequest}
import play.api.{Logger, Play}
import things.ReferenceCounting

import scala.collection.convert.Wrappers.JListWrapper
import scala.collection.mutable

object City {
  private case object AllAreasCreated
  private case object AllRoadsCreated
  private case object AllCrossingsCreated
  private case object AllCrossingsFinished
  private case object AllRoutingStarted
  private case object CityStarted

  private case object ShutdownDone

  case class SystemShuttingDown private[City] (error: Option[String]) extends Event
}

/**
 * Contains the whole infrastructure. Helps the startup of the system.
 */
sealed class City extends Actor {
  import context._

  val infraConf = Play.current.configuration.getConfig("infra").get

  val areasConfig = JListWrapper(infraConf.getConfigList("city.area").get)

  override val supervisorStrategy = OneForOneStrategy() {case _ => Stop}

  val cityEventBus = actorOf(Props[EventBus], "eventBus")

  val referenceCounterRouter = actorOf(
    ReferenceCounter.props(city = self,
      timeout = new Millis(infraConf.getMilliseconds("global.referenceCounter.timeout").get))
      .withRouter(ConsistentHashingRouter(nrOfInstances = 5)),
    name = "referenceCounter")
  ReferenceCounting.referenceCounterPromise.success(referenceCounterRouter)

  override def preStart() = {
    Logger.info("City created")
  }

  /**
   * @return the initial `receive` function
   */
  def receive = {
    val remoteAgentURLs = JListWrapper(infraConf.getStringList("city.remoteSystems").get)

    if (remoteAgentURLs.isEmpty) {
      normal(Set.empty)
    } else {
      connectingToRemoteAgents(remoteAgentURLs)
    }
  }

  /**
   * Transition function to the `connectingToRemoteAgents` state. Sends initial messages to the remote agents.
   * @param remoteAgentURLs addresses of the remote agents
   * @return `receive` function for this state
   */
  def connectingToRemoteAgents(remoteAgentURLs: Iterable[String]) : Receive = {
    val remoteAgents = mutable.Set[ActorRef]()
    remoteAgentURLs foreach {ap =>
    actorSelection(ap) ! CreateReferenceCounterRelay(referenceCounterRouter)}

    {
      case ReferenceCounterRelayCreated =>
        remoteAgents += sender
        if (remoteAgents.size == remoteAgentURLs.size)
          become(normal(remoteAgents.toSet))
    }
  }

  /**
   * Transition function to the `normal` state. Creates the areas.
   * @param remoteAgents set of references of the remote agents
   * @return `receive` function for this state
   */
  def normal(remoteAgents: Set[ActorRef]) : Receive = {
    val areaC = context.actorOf(
      Container.propsFromRange(
        range = areasConfig.indices,
        createChildProp = {i => Area.props(i, areasConfig(i), infraConf)},
        createdMessage = AllAreasCreated)
      , "area")

    {
      case ContainerPopulated(AllAreasCreated, _) =>
        Logger.info("All Areas created")
        areaC ! SendToContained(Area.CreateCrossings, AllCrossingsCreated)

      case AllCrossingsCreated =>
        Logger.info("All Crossings created")
        areaC ! SendToContained(Area.CreateRoads, AllRoadsCreated)

      case AllRoadsCreated =>
        Logger.info("All Roads created")
        areaC ! SendToContained(Area.FinishCrossings, AllCrossingsFinished)

      case AllCrossingsFinished =>
        Logger.info("All Crossings finalized")
        areaC ! SendToContained(Area.StartRouting, AllRoutingStarted)

      case AllRoutingStarted =>
        Logger.info("Routing configured")
        areaC ! SendToContained(Area.StartThings, CityStarted)

      case CityStarted =>
        areaC ! WatchChildren
        watch(areaC)
        Logger.info("City started")


      case GetTimeRequest(callback) =>
        sender ! GetTimeRequest.response(
          callback = callback,
          now = Timing.timeElapsed,
          dayDuration = Timing.dayDuration)


      case ShutdownRequest() =>
        Logger.info(s"Shutting down city")
        become(waitingForShutdown(areaC, remoteAgents))

      case Terminated(a) =>
        become(shuttingDown(remoteAgents, error = Some(s"Actor $a died, shutting down")))

      case ChildDied(a) =>
        become(shuttingDown(remoteAgents, error = Some(s"Area $a died, shutting down")))

      case EntityLost(id) =>
        become(shuttingDown(remoteAgents, error = Some(s"Entity $id lost, shutting down")))
    }
  }

  /**
   * Transition function to the `waitingForShutdown` state. Sends the [[Area.Shutdown]] message.
   * @param areaContainer reference of the [[Area]]s container
   * @param remoteAgents set of references of the remote agents
   * @return `receive` function for this state
   */
  def waitingForShutdown(areaContainer: ActorRef, remoteAgents: Set[ActorRef]) : Receive = {
    areaContainer ! SendToContained(Area.Shutdown, ShutdownDone)

    {
      case ShutdownDone =>
        become(shuttingDown(remoteAgents, error = None))
    }
  }

  /**
   * Transition function to the `down` state. Shuts down the system.
   * @param remoteAgents set of references of the remote agents
   * @param error description of the error occurred, if any
   * @return `receive` function for this state (ignoring all messages)
   */
  def shuttingDown(remoteAgents: Set[ActorRef], error: Option[String]) : Receive = {
    error foreach (err => Logger.error(err))
    cityEventBus ! SystemShuttingDown(error)

    new Thread(new Runnable {
      override def run() {
        Thread.sleep(100)

        remoteAgents foreach {a =>
          Logger.info(s"Shutting down remote ActorSystem $a")
          a ! Area.Shutdown}

        Logger.info(s"Shutting down local ActorSystem")
        children foreach system.stop
        system.shutdown()
        Thread.sleep(100)

        Logger.info(s"Stopping JVM")
        Thread.sleep(1)
        sys.exit()
      }
    }).start()

    {case _ => }
  }
}
