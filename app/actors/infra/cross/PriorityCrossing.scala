package actors.infra.cross

import actors.infra.Container.{ContainedInfo, ContainedReceived}
import actors.infra._
import actors.infra.cross.CrossingBase.{CrossingId, LaneId, LaneSelector}
import actors.infra.cross.PriorityCrossing.TryForward
import actors.infra.position.TrajectoryCmp.Trajectory
import actors.infra.position._
import actors.notifications.EventBus.{ThingEnteredStructure, ThingExitedArea}
import akka.actor.{ActorRef, Props}
import controllers.Socket.{SendJson, JsonInbound}
import controllers.Timing
import controllers.Timing.{Millis, Time}
import controllers.json.ActorRefSerializer
import play.api.libs.json.{JsString, Json}
import things.{Pedestrian, RoadThing, Vehicle}

import scala.collection.immutable.SortedMap
import scala.collection.mutable

object PriorityCrossing {
  /**
   * Prepares [[akka.actor.Props]] for a PriorityCrossing.
   * @param crossId id of this Crossing
   * @param x position on the x axis
   * @param y position on the y axis
   * @param laneSelectors for each [[position.ConnectionPosition]],
   *                      the sequence of lane descriptors [[CrossingBase.LaneSelector]] on that side
   * @param priorities priority of each side of the Crossing ([[position.ConnectionPosition]])
   * @param eventBus reference to the [[actors.notifications.EventBus]] of this Area
   */
  def getProps(crossId: CrossingId, x: Double, y: Double,
               laneSelectors: Map[ConnectionPosition, Seq[LaneSelector]],
               transitTime: Millis, pedestrianCrossingTime: Millis,
               priorities: Map[ConnectionPosition, Int],
               eventBus: ActorRef) =
    Props(new PriorityCrossing(
      crossId, x, y, laneSelectors, transitTime, pedestrianCrossingTime, priorities, eventBus))

  protected sealed case class TryForward(time: Time)
}

/**
 * Manages a Crossing without traffic lights. Uses both a [[VehicleHandler]] and a [[PedestrianHandler]].
 * @param crossId id of this Crossing
 * @param x position on the x axis
 * @param y position on the y axis
 * @param transitTime time needed by a vehicle to go through the crossing
 * @param pedestrianCrossingTime time needed by a pedestrian to cross the road
 * @param laneSelectors for each [[position.ConnectionPosition]],
 *                      the sequence of lane descriptors [[CrossingBase.LaneSelector]] on that side
 * @param priorities priority of each side of the Crossing ([[position.ConnectionPosition]])
 * @param eventBus reference to the [[actors.notifications.EventBus]] of this Area
 */
class PriorityCrossing protected (crossId: CrossingId, x: Double, y: Double,
                                  laneSelectors: Map[ConnectionPosition, Seq[LaneSelector]],
                                  transitTime: Millis, pedestrianCrossingTime: Millis,
                                  priorities: Map[ConnectionPosition, Int],
                                  eventBus: ActorRef)
  extends CrossingBase(crossId, laneSelectors, transitTime, pedestrianCrossingTime, eventBus) {

  import context._

  protected def setInfo(): Unit = {
    parent ! ContainedInfo(Json.obj(
      "pos" -> Json.obj("x" -> x, "y" -> y),
      "type" -> "priority"
    ))
  }

  private case class PedestrianTransitTimeout(end: Time, reference: AnyRef)
  private case class VehicleTransitTimeout(end: Time, reference: AnyRef)

  /**
   * Transition function to the `ready` state. Creates the [[HandlerContext]] implementation,
   * the [[VehicleHandler]], the [[PedestrianHandler]] and the [[GuardState]] implementation.
   * @return `receive` function for this state
   */
  override final protected def ready(crossingContext: CrossingContext): Receive = {
    import crossingContext._

    setInfo()

    val roadsForPriority: SortedMap[LaneId, Seq[ConnectionPosition]] =
      SortedMap[LaneId, Seq[ConnectionPosition]]()(Ordering[LaneId].reverse) ++ {
        val prs = priorities.values.toList.distinct
        val v = prs map {p => priorities.keys.toList.filter(priorities(_) == p)}
        ( prs zip v ).toMap
      }

    val oLog = log
    val handlerContext = new HandlerContext {
      val existingVehicleRoadsOut: Seq[ConnectionPosition] = vehicleRoadsOut.keys.toSeq
      val existingPedestrianRoadSidesOut: Seq[PedestrianConnectionPosition] = pedestrianRoadsOut.keys.toSeq

      def forwardVehicle(vehicle: Vehicle, to: ConnectionPosition, time: Time) {
        vehicleRoadsOut(to) ! VehicleFromCrossing(vehicle, time)
      }

      def forwardPedestrian(pedestrian: Pedestrian, to: PedestrianConnectionPosition, time: Time) {
        pedestrianRoadsOut(to) ! PedestrianFromCrossing(pedestrian, time)
      }

      def sendThingEnteredEvent(thing: RoadThing, duration: Millis,
                                vertex: Option[PedestrianPosition] = None) {
        eventBus ! ThingEnteredStructure(thing, "cross",
          vertex = vertex,
          duration = Some(duration))
      }

      def sendCSM(to: (ConnectionPosition, LaneId), time: Time) {
        vehicleLanesIn(to) ! CanSendMore(time)
      }

      def requestPedestrianTransitTimeout(end: Time, reference: AnyRef) {
        system.scheduler.scheduleOnce(
          Timing.waitUntil(end).fd, self, PedestrianTransitTimeout(end, reference))
      }
      def requestVehicleTransitTimeout(end: Time, reference: AnyRef) {
        system.scheduler.scheduleOnce(
          Timing.waitUntil(end).fd, self, VehicleTransitTimeout(end, reference))
      }

      val log = oLog
    }

    val guards = mkGuards(
      mutable.Set[Trajectory](),
      mutable.Map() ++ ConnectionPosition.allCPs.distinct.map(_ -> false).toMap,
      mutable.Map() ++ ConnectionPosition.allCPs.distinct.map(_ -> 0).toMap)

    val vh = new VehicleHandler(roadsForPriority, transitTime, handlerContext, guards)

    val ph = new PedestrianHandler(pedestrianCrossingTime, handlerContext, guards)

    mkReadyReceive(crossingContext, guards, vh, ph)
  }

  /**
   * @return the appropriate [[GuardState]]
   */
  protected def mkGuards = new PriorityGuards(_, _, _)

  protected class PriorityGuards(final val currentVehicleTransits: mutable.Set[Trajectory],
                                 final val currentPedestriansWaiting: mutable.Map[ConnectionPosition, Boolean],
                                 final val currentPedestrianTransits: mutable.Map[ConnectionPosition, LaneId]
                                  ) extends GuardState {

    final def pedestrianMayPass(road: ConnectionPosition): Boolean =
      !pedestrianRedRoads.contains(road) &&
        !currentVehicleTransits.exists(tr => tr.from == road || tr.to == road)

    final def vehicleMayPass(from: ConnectionPosition, fromLane: LaneId, to: ConnectionPosition): Boolean =
      !vehicleRedRoads.contains(from) &&
        Seq(from, to).forall(cp =>
          currentPedestrianTransits(cp) == 0 &&
            (pedestrianRedRoads.contains(cp) || !currentPedestriansWaiting(cp)))

    override def toString: String =
      s"currentVehicleTransits    = $currentVehicleTransits \n" +
        s"currentPedestriansWaiting = $currentPedestriansWaiting \n" +
        s"currentPedestrianTransits = $currentPedestrianTransits \n"
  }

  /**
   * @return the actual `receive` function for the `ready` state. Handles pedestrian and vehicle forwarding.
   */
  protected def mkReadyReceive(crossingContext: CrossingContext, guards: GuardState,
                               vh: VehicleHandler, ph: PedestrianHandler): Receive = {
    import crossingContext._


    def checkAreaChange(thing: RoadThing, sender: ActorRef) {
      if (crossId.containingArea != roadIdOf(sender).containingArea)
        sender ! ThingExitedArea(thing, roadIdOf(sender).containingArea, crossId.containingArea)
    }

    {
      case VehicleFromLane(veh, nt, t) =>
        checkAreaChange(veh, sender)
        vh.acceptVehicle(vehicleLanesInRev(sender), veh, nt, t)

      case PedestrianFromSidewalk(ped, end, t) =>
        checkAreaChange(ped, sender)
        ph.acceptPedestrian(
          pedestrianSidewalksInRev(sender), ped, end, t)

      case CanSendMore(t) =>
        vh.openVehicleGuardTo(vehicleRoadsOutRev(sender), t)

      case PedestrianTransitTimeout(exp, ref) =>
        ph.transitTimeout(exp, ref)
        vh.tryForward(exp)

      case VehicleTransitTimeout(exp, ref) =>
        vh.transitTimeout(exp, ref)
        ph.tryForward(exp)

      case TryForward(t) =>
        ph.tryForward(t)
        vh.tryForward(t)

      case Area.Shutdown =>
        become(down)
        children foreach (_ ! Area.Shutdown)

        parent ! ContainedReceived

      case JsonInbound(_, _) =>
        sender ! SendJson(JsString(ActorRefSerializer.serAR(self) + "\n"
          + guards.toString + ph.toString + vh.toString))
    }
  }
}