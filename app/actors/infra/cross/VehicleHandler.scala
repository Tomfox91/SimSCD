package actors.infra.cross

import actors.infra.cross.CrossingBase.LaneId
import actors.infra.position
import actors.infra.position.TrajectoryCmp.Trajectory
import actors.infra.position.{ConnectionPosition, TrajectoryCmp}
import controllers.Timing.{Time, Millis}
import things.Vehicle

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * Handles the forwarding decisions for vehicles.
 * @param priorities map from the priority levels to the [[position.ConnectionPosition]]
 *                   of the roads at each level
 * @param transitTime the time needed by a vehicle to go through the crossing
 * @param context the [[HandlerContext]]
 */
class VehicleHandler private[cross](priorities: SortedMap[Int, Seq[ConnectionPosition]],
                                    transitTime: Millis, context: HandlerContext,
                                    guardsState: GuardStateForVehicles) {
  import context._
  import guardsState._

  private sealed case class PendingVehicle(vehicle: Vehicle,
                                           from: (ConnectionPosition, LaneId),
                                           nextTurn: ConnectionPosition) {
    val trajectory = Trajectory(from._1, nextTurn)

    override def toString = s"PenVeh(${vehicle.id}, $nextTurn)"
  }

  private val pendingVehicles: Map[ConnectionPosition, ListBuffer[PendingVehicle]] =
    priorities.values.flatten.map(cp => cp -> new ListBuffer[PendingVehicle]()).toMap

  private val exitGuards: mutable.Map[ConnectionPosition, Boolean] =
    mutable.Map() ++ existingVehicleRoadsOut.map(_ -> true)


  /**
   * Adds the vehicle to the pending vehicles structure and tries to forward it
   * @param from the [[position.ConnectionPosition]] and `LaneId` of the lane the vehicle comes from
   * @param vehicle the vehicle
   * @param nextTurn the turn the vehicle wants to take
   */
  def acceptVehicle(from: (ConnectionPosition, LaneId),
                    vehicle: Vehicle, nextTurn: ConnectionPosition,
                    t: Time) {
    pendingVehicles(from._1) += PendingVehicle(vehicle, from, nextTurn)

    log.info(s"VH: received vehicle ${vehicle.id}")
    sendThingEnteredEvent(vehicle, transitTime)
    tryForward(t)
  }

  /**
   * Opens the guard to `to`
   * @param to the [[position.ConnectionPosition]] of the road that sent the message
   */
  def openVehicleGuardTo(to: ConnectionPosition, t: Time) {
    exitGuards(to) = true
    tryForward(t)
  }

  /**
   * Forwards the maximal set of vehicles such that:
   *   - no two forwarded vehicles have colliding trajectories
   *   - every forwarded vehicle has higher priority or precedence over
   *     non-forwarded vehicle that are not blocked
   */
  def tryForward(t: Time) {
    import TrajectoryCmp._

    val toSend = ListBuffer[PendingVehicle]()
    for (
      // for every priority level
      (prior, cpl) <- priorities;
      // for every road at that level
      cp <- cpl;
      // for every vehicle from that road
      v <- pendingVehicles(cp)
      // if the vehicle can exit
      if vehicleMayPass(cp, v.from._2, v.nextTurn) && exitGuards(v.nextTurn)
      // if the vehicle does not collide with already exiting vehicles
      if toSend.filterNot(_.from._1 == v.from._1) // already exiting vehicles from different sources
        .forall(ev => doNotCollide(v.trajectory, ev.trajectory))
      // if the vehicle has precedence over all other vehicles coming
      // from other roads at the same priority level
      if priorities(prior).filterNot(_ == cp) // roads different from s with same priority
        .flatMap(pendingVehicles(_)) // vehicles from those roads
        .forall(ov => hasPrecedence(v.trajectory, ov.trajectory))
    ) {
      toSend += v
    }

    for (v <- toSend) {
      requestVehicleTransitTimeout(t + transitTime, TransitingVehicle(v))
      currentVehicleTransits += v.trajectory
      pendingVehicles(v.from._1) -= v
      exitGuards(v.nextTurn) = false
    }
  }

  private case class TransitingVehicle(pVeh: PendingVehicle)

  /**
   * Called by the crossing when a timeout ends.
   */
  def transitTimeout(exp: Time, reference: AnyRef) = reference match {
    case TransitingVehicle(pVeh) =>
      currentVehicleTransits -= pVeh.trajectory
      sendCSM(to = pVeh.from, time = exp)
      forwardVehicle(pVeh.vehicle, to = pVeh.nextTurn, time = exp)

    case x => throw new IllegalArgumentException(x.toString)
  }


  override def toString =
    s"pendingVehicles = ${pendingVehicles.filter(_._2.nonEmpty)}\n" +
    s"exitGuards      = $exitGuards\n"
}
