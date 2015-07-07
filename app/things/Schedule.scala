package things

import actors.infra.road.Road.RoadId
import controllers.Timing
import controllers.Timing.{DayTime, dayTimeFromConfiguration}
import play.api.Configuration
import play.api.libs.json._

import scala.collection.SortedMap

object Schedule {

  /**
   * Converts the schedule to a JSON object
   */
  implicit val writes = new Writes[Schedule] {
    def writes(s: Schedule) =
      JsObject(s.schedule.map {case (t, d) => (t.toString, JsString(d.toString))}.toSeq)
  }

  /**
   * Creates a schedule from configuration
   * @param confList configuration parameters
   * @return the schedule created
   */
  def fromConfiguration(confList: Seq[Configuration]): Schedule = {
    val rows: Seq[(DayTime, RoadId)] =
      confList.map(c => dayTimeFromConfiguration(c) ->
      RoadId(c.getString("aid").get, c.getString("rid").get))

    new Schedule(SortedMap(rows: _*))
  }
}

/**
 * A schedule is a sequence of places to be reached and times to leave the previous place.
 * @param schedule schedule of an entity
 */
sealed class Schedule (val schedule: SortedMap[DayTime, RoadId]) extends Serializable {
  def current = {
    val time = Timing.timeOfDay
    schedule.filter(_._1 <= time).lastOption.getOrElse(schedule.last)
  }

  def next = {
    val time = Timing.timeOfDay
    schedule.find(_._1 > time).getOrElse(schedule.head)
  }

  def currentDestination: RoadId = current._2
  def currentTime: DayTime = current._1

  def nextDestination: RoadId = next._2
  def nextTime: DayTime = next._1

  override def toString = {
    schedule.map{case (t, p) => s"$t: ${p.areaId}/${p.roadId}"}.reduce(_ ++ ", " ++ _)
  }
}
