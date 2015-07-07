package things

import actors.infra.road.Road.RoadId
import play.api.Configuration

import scala.collection.convert.Wrappers.JListWrapper

object PersonSpec {

  /**
   * Create a person specification form configuration
   * @param conf configurations parameters, useful to build the person
   * @param road road where the person start to live
   * @return the PersonSpec created
   */
  def fromConfiguration(conf: Configuration, road: RoadId) = {
    val id = conf.getString("id").get
    val carIdO = conf.getString("carId")
    val schedule = Schedule.fromConfiguration(JListWrapper(conf.getConfigList("schedule").get))

    PersonSpec(id, schedule, carIdO.map((carId) => (carId, road)))
  }
}

/**
 * A person. Has a schedule to follow.
 * @param idp identifier
 */
sealed abstract class Person (idp: String) extends RoadThing(idp) {
  def hasCar: Boolean

  def schedule: Schedule
  def destination = schedule.currentDestination

  val infoString = s"schedule: $schedule"
  override def toString = s"Person $id: $infoString"
}

/**
 * A person who owns a car.
 * @param id identifier of this person
 * @param schedule schedule of this person
 * @param carId identifier of person's car
 * @param carParkedIn road where the car is parked
 */
sealed case class PersonWithCar(id: String, schedule: Schedule, carId: String,
                                carParkedIn: Option[RoadId] = None) extends Person(id) {
  def hasCar = true

  def withCarParked(in: RoadId) = PersonWithCar(id, schedule, carId, Some(in))
  def withoutCarParked = PersonWithCar(id, schedule, carId, None)

  override val infoString = s"car: $carId schedule: $schedule"
}

/**
 * A person who does not have a car.
 * @param id identifier of this person
 * @param schedule schedule of this person
 */
sealed case class PersonWithoutCar(id: String, schedule: Schedule) extends Person(id) {
  def hasCar = false
}

/**
 * A person specification.
 * @param id identifier of this person
 * @param schedule schedule of this person
 * @param car car that is owned by this person
 */
sealed case class PersonSpec(id: String, schedule: Schedule, car: Option[(String, RoadId)]) {
  def makePerson = car match {
    case Some((carId, parkedIn)) => PersonWithCar(id, schedule, carId, Some(parkedIn))
    case None                    => PersonWithoutCar(id, schedule)
  }
}