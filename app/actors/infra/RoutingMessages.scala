package actors.infra

import actors.infra.position.TurnRef
import actors.infra.road.Road.{AdditionalRoutingInformation, RoutingId}
import controllers.Timing.Millis

/**
 * Supplies routing data from a [[actors.infra.road.Road]] to a [[actors.infra.cross.CrossingBase]]
 * @param data routing data
 */
sealed case class RoutingDataFromRoad(data: Map[RoutingId, (Millis, Int)])

/**
 * Supplies routing data from a [[actors.infra.cross.CrossingBase]] to a [[actors.infra.road.Road]]
 * @param data routing data
 * @param nextTurn next destination
 * @tparam TR the type of the routing information
 *            (usually [[position.ConnectionPosition]] or [[position.PedestrianConnectionPosition]])
 */
sealed case class RoutingDataFromCross[TR <: TurnRef](data: Map[RoutingId, (Millis, Int)],
                                                      nextTurn: TR)

/**
 * Supplies data for routing table update
 * @param data routing data
 * @param additionalInfo collection of routing identifier
 * @tparam TR the type of the routing information
 *            (usually [[position.ConnectionPosition]] or [[position.PedestrianConnectionPosition]])

 */
sealed case class RoutingTableUpdate[+TR <: TurnRef](data: Map[RoutingId, (TR, Millis)],
                                                     additionalInfo: Set[AdditionalRoutingInformation])
