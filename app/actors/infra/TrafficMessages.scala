package actors.infra

import actors.infra.position.{ConnectionPosition, PedestrianConnectionPosition}
import controllers.Timing.Time
import things.{Pedestrian, Vehicle}

/**
 * Specifies that a vehicle is arriving to a [[actors.infra.road.Road]]
 * from a [[actors.infra.cross.CrossingBase]]
 * @param vehicle vehicle arriving from a crossing
 * @param timestamp the time when the event happened
 */
sealed case class VehicleFromCrossing(vehicle: Vehicle,
                                      timestamp: Time)

/**
 * Specifies that a vehicle is entering a [[actors.infra.road.Lane]]
 * from a [[actors.infra.road.Road]]
 * @param vehicle vehicle moving
 * @param turn next turn
 * @param timestamp the time when the event happened
 */
sealed case class VehicleToLane(vehicle: Vehicle, turn: ConnectionPosition,
                                timestamp: Time)

/**
 * Specifies that a vehicle is entering a [[actors.infra.cross.CrossingBase]]
 * from a [[actors.infra.road.Lane]]
 * @param vehicle vehicle arriving from a lane
 * @param turn next turn
 * @param timestamp the time when the event happened
 */
sealed case class VehicleFromLane(vehicle: Vehicle, turn: ConnectionPosition,
                                  timestamp: Time)

/**
 * Specifies that a pedestrian is arriving to a [[actors.infra.road.Road]]
 * from a [[actors.infra.cross.CrossingBase]]
 * @param pedestrian pedestrian arriving from a crossing
 * @param timestamp the time when the event happened
 */
sealed case class PedestrianFromCrossing(pedestrian: Pedestrian,
                                         timestamp: Time)

/**
 * Specifies that a pedestrian is entering a [[actors.infra.road.Sidewalk]]
 * from a [[actors.infra.road.Road]]
 * @param pedestrian pedestrian moving
 * @param nextTurn next turn
 * @param shortcut whether the pedestrian can skip the wait for the Lane time
 * @param timestamp the time when the event happened
 */
sealed case class PedestrianToSidewalk(pedestrian: Pedestrian,
                                       nextTurn: PedestrianConnectionPosition,
                                       timestamp: Time,
                                       shortcut: Boolean = false)

/**
 * Specifies that a pedestrian is entering a [[actors.infra.cross.CrossingBase]]
 * from a [[actors.infra.road.Sidewalk]]
 * @param pedestrian pedestrian arriving from sidewalk
 * @param nextTurn next turn
 * @param timestamp the time when the event happened
 */
sealed case class PedestrianFromSidewalk(pedestrian: Pedestrian,
                                         nextTurn: PedestrianConnectionPosition,
                                         timestamp: Time)

/**
 * Specifies that an entity can send more vehicles to the sender (hand-off protocol)
 * @param timestamp the time when the event happened
 */
sealed case class CanSendMore(timestamp: Time)