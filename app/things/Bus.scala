package things

import actors.infra.position.ConnectionPosition
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import things.Bus.{BusCategory, BusLine}
import things.Vehicle.VehicleCategory

object Bus {
  case object BusCategory extends VehicleCategory

  type BusLine = String
}

object BusSpec {
  private val defaultCapacity: Int =
    ConfigFactory.load.getInt("infra.defaults.bus.capacity")

  def apply(id: String, path: String, line: String, capacity: Int) =
    new BusSpec(id, path.map(ConnectionPosition.apply), line, capacity)

  /**
   * Create a bus specification from configuration.
   * @param conf configuration parameters, useful to build this bus
   * @return the BusSpec created
   */
  def fromConfiguration(conf: Configuration): BusSpec = BusSpec(
    id = conf.getString("id").get,
    path = conf.getString("path").get,
    line = conf.getString("line").get,
    capacity = conf.getInt("capacity").getOrElse(defaultCapacity)
  )
}

/**
 * A bus is a [[things.RoadThing]] that carries pedestrians.
 * @param id identifier of this bus
 * @param path path of this bus in the city
 * @param line name of the bus line
 * @param capacity capacity of this bus
 * @param passengers pedestrians that is contained in this bus
 */
sealed case class Bus(id: String, path: Seq[ConnectionPosition], line: BusLine,
                      capacity: Int, passengers: Seq[Pedestrian] = Seq()
                       ) extends Vehicle(id) with HasNextTurn {
  assert(capacity >= passengers.size, s"Overfull bus $id")

  override def category = BusCategory
  override def spacesUsed = 2
  
  private def shiftPath = path.drop(1) :+ path.head

  override def nextTurn(): (Bus, ConnectionPosition) =
    (Bus(id, shiftPath, line, capacity, passengers), path.head)

  def withPassengers(pass: Seq[Pedestrian]) = Bus(id, path, line, capacity, pass)
}

/**
 * A bus specification.
 * @param id identifier of this bus
 * @param path path of this bus in the city
 * @param line name of the bus line
 * @param capacity capacity of this bus
 */
sealed case class BusSpec(id: String, path: Seq[ConnectionPosition], line: BusLine,
                          capacity: Int) {

  def makeBus = Bus(id, path, line, capacity)
}