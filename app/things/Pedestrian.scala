package things

import actors.infra.road.Road.RoadId

/**
 * A pedestrian is a person currently moving through the city.
 * @param person the person contained in this pedestrian
 * @param destinationOverride if set, a destination the pedestrian has to reach
 */
sealed case class Pedestrian(person: Person,
                             destinationOverride: Option[RoadId] = None
                              ) extends RoadThing(person.id) with HasNextDestination {

  override val id: String = person.id
  override def destination: RoadId = destinationOverride getOrElse person.destination
}