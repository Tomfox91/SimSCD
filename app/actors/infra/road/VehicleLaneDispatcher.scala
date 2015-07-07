package actors.infra.road

import actors.infra.cross.CrossingBase.LaneSelector
import actors.infra.position.ConnectionPosition
import actors.infra.road.Road.RoadId
import akka.actor.ActorRef
import play.api.Logger
import things.Vehicle

/**
 * A dispatcher selects the correct lane for a vehicle.
 * @param lanes list of lanes available in the road
 * @param owner road that owns the lanes
 */
class VehicleLaneDispatcher private[road] (lanes: Seq[(LaneSelector, ActorRef)], owner: RoadId) {
  /**
   * Returns the right [[Lane]] for a vehicle.
   * @param vehicle vehicle who has to be routed
   * @param nextTurn next turn of the vehicle
   * @return [[akka.actor.ActorRef]] of the selected lane
   */
  def laneFor(vehicle: Vehicle, nextTurn: ConnectionPosition): ActorRef = {
    lanes find {case (sel, _) =>
      sel.position.fold(true)(_.contains(nextTurn)) &&
        sel.category.fold(true)(_ == vehicle.category)
    } getOrElse {
      Logger.warn(s"RoadNavigator of $owner: cannot properly dispatch $vehicle going $nextTurn")
      lanes.head
    }
  }._2
}
