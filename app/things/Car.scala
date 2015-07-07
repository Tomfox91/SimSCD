package things

import actors.infra.road.Road.RoutableId
import things.Car.CarCategory
import things.Vehicle.VehicleCategory

object Car {
  case object CarCategory extends VehicleCategory
}

sealed trait CarLike {
  def id: String
  def ownerId: String
}

/**
 * A car is used by its owner to move around the city.
 * @param person person who owns this car
 * @param destinationOverride if set, a destination the car has to reach
 */
sealed case class Car(person: PersonWithCar,
                      destinationOverride: Option[RoutableId] = None
                       ) extends Vehicle(person.carId) with HasNextDestination with CarLike {

  def id = person.carId
  def ownerId = person.id
  override def category = CarCategory

  override def destination: RoutableId = destinationOverride getOrElse person.destination

  def withDestinationOverride(dest: RoutableId) = Car(person, Some(dest))
  def withoutDestinationOverride = Car(person, None)
}

/**
 * A car that is currently parked.
 * @param id identifier of this car
 * @param ownerId identifiers of the pedestrian who owns this car
 */
sealed case class ParkedCar(id: String, ownerId: String) extends RoadThing(id) with CarLike

/**
 * A parked car specification.
 * @param id identifier of this car
 * @param ownerId identifiers of the pedestrian who owns this car
 */
sealed case class ParkedCarSpec(id: String, ownerId: String) {
  def makeParkedCar = ParkedCar(id, ownerId)
}