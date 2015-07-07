package things

import things.Bus.BusCategory
import things.Car.CarCategory
import things.Vehicle.VehicleCategory


object Vehicle {
  /**
   * A category of vehicles.
   */
  trait VehicleCategory

  /**
   * Converts a string to [[VehicleCategory]].
   */
  def categoryFor: String => VehicleCategory = {
    case "car" => CarCategory
    case "bus" => BusCategory
  }
}

/**
 * Superclass of all vehicles.
 * @param idp identifier of the vehicle used by the reference counter
 */
abstract class Vehicle (idp: String) extends RoadThing(idp) {
  def spacesUsed: Int = 1
  def category: VehicleCategory
}
