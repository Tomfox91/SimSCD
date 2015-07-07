package actors.infra.cross

import actors.infra.cross.CrossingBase.LaneId
import actors.infra.position
import actors.infra.position.TrajectoryCmp.Trajectory
import actors.infra.position.{ConnectionPosition, PedestrianConnectionPosition, PedestrianPosition}
import akka.event.LoggingAdapter
import controllers.Timing.{Millis, Time}
import things.{Pedestrian, RoadThing, Vehicle}

import scala.collection.mutable

/**
 * Used by [[PedestrianHandler]] to interact with the guards.
 */
trait GuardStateForVehicles {
  /**
   * @return the set of the [[actors.infra.position.TrajectoryCmp.Trajectory]]es currently in use by vehicles.
   */
  def currentVehicleTransits: mutable.Set[Trajectory]

  /**
   * @return True iff a vehicle may transit from `from` to `to`.
   */
  def vehicleMayPass(from: ConnectionPosition, fromLane: LaneId,
                     to: ConnectionPosition): Boolean
}


/**
 * Used by [[VehicleHandler]] to interact with the guards.
 */
trait GuardStateForPedestrians {
  /**
   * @return a map from the roads to a Boolean, which is true iff
   *         some pedestrian is currently waiting to cross that road
   */
  def currentPedestriansWaiting: mutable.Map[ConnectionPosition, Boolean]

  /**
   * @return a map from the roads to the number of pedestrian currently crossing each road
   */
  def currentPedestrianTransits: mutable.Map[ConnectionPosition, Int]
  def pedestrianMayPass(road: ConnectionPosition): Boolean
}

/**
 * Joins [[GuardStateForPedestrians]] and [[GuardStateForVehicles]].
 */
trait GuardState extends GuardStateForPedestrians with GuardStateForVehicles {
  def currentVehicleTransits: mutable.Set[Trajectory]

  def currentPedestriansWaiting: mutable.Map[ConnectionPosition, Boolean]
  def currentPedestrianTransits: mutable.Map[ConnectionPosition, Int]

  def vehicleRedRoads: Set[ConnectionPosition] = Set()
  def pedestrianRedRoads: Set[ConnectionPosition] = Set()

  def vehicleMayPass(from: ConnectionPosition, fromLane: LaneId,
                     to: ConnectionPosition): Boolean
  def pedestrianMayPass(road: ConnectionPosition): Boolean
}


/**
 * Used by handlers to interact with the crossings.
 */
trait HandlerContext {
  /**
   * @return the [[position.ConnectionPosition]] of the connected roads
   */
  def existingVehicleRoadsOut: Seq[ConnectionPosition]

  /**
   * @return the [[position.PedestrianConnectionPosition]] of the connected sidewalks
   */
  def existingPedestrianRoadSidesOut: Seq[PedestrianConnectionPosition]

  /**
   * @return the [[position.ConnectionPosition]] of the connected sidewalks
   */
  def existingPedestrianRoadsOut: Seq[ConnectionPosition] =
    existingPedestrianRoadSidesOut.map(_.position).distinct

  /**
   * Requests a timeout for a pedestrian transit
   * @param end the end of the timeout
   * @param reference an arbitrary reference to identify the pedestrian
   */
  def requestPedestrianTransitTimeout(end: Time, reference: AnyRef)

  /**
   * Requests a timeout for a vehicle transit
   * @param end the end of the timeout
   * @param reference an arbitrary reference to identify the vehicle
   */
  def requestVehicleTransitTimeout(end: Time, reference: AnyRef)

  /**
   * Invoked when the Handler wants to forward a vehicle.
   * @param vehicle the vehicle
   * @param to the [[position.ConnectionPosition]] of the receiving road
   */
  def forwardVehicle(vehicle: Vehicle, to: ConnectionPosition, time: Time)

  /**
   * Invoked when the Handler wants to forward a pedestrian.
   * @param pedestrian the pedestrian
   * @param to the [[position.PedestrianConnectionPosition]] of the receiving road
   */
  def forwardPedestrian(pedestrian: Pedestrian, to: PedestrianConnectionPosition, time: Time)

  /**
   * Invoked to produce an [[actors.notifications.EventBus.ThingEnteredStructure]] event.
   * @param thing the [[things.RoadThing]]
   * @param vertex the point of the Crossing the event happened in (if any)
   */
  def sendThingEnteredEvent(thing: RoadThing, duration: Millis,
                            vertex: Option[PedestrianPosition] = None)

  /**
   * Invoked to send a [[actors.infra.CanSendMore]] to a lane.
   * @param to the [[position.ConnectionPosition]] and `LaneId` of the desired recipient
   */
  def sendCSM(to: (ConnectionPosition, LaneId), time: Time)

  def log: LoggingAdapter
}
