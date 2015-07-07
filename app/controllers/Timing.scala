package controllers

import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.api.libs.json.{JsNumber, Writes}

import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.math.{abs, max, round, floor}
import scala.util.Random

/**
 * Contains various methods to help with timing.
 */
object Timing {
  /**
   * Represents a time difference
   * @param _millis the time difference, in milliseconds
   */
  class Millis (val _millis: Long) extends AnyVal
      with Ordered[Millis] with Serializable {
    def + (oth: Millis): Millis = new Millis(_millis + oth._millis)
    def - (oth: Millis): Millis = new Millis(_millis - oth._millis)
    def|-|(oth: Millis): Millis = new Millis(abs(_millis - oth._millis))
    def * (oth: Int):    Millis = new Millis(_millis * oth)
    def / (oth: Int):    Millis = new Millis(_millis / oth)

    def randomPart = new Millis(abs(Random.nextLong()) % _millis)

    def fd: FiniteDuration = _millis.milliseconds
    def compare(oth: Millis): Int = _millis.compare(oth._millis)
    override def toString = s"${_millis} ms"
  }

  /**
   * Represents the time in the day
   * @param _time the time in the day, in milliseconds
   */
  class DayTime private[Timing] (val _time: Long) extends AnyVal
      with Ordered[DayTime] with Serializable {
    def compare(oth: DayTime): Int = _time.compare(oth._time)
    override def toString = f"${_time * 24.0 / dayDuration._millis}%.3f"
  }

  /**
   * Represents an absolute time
   * @param _time the absolute time, in milliseconds
   */
  class Time private[Timing] (val _time: Long) extends AnyVal
      with Ordered[Time] with Serializable {
    def + (oth: Millis): Time = new Time(_time + oth._millis)
    def - (oth: Time): Millis = new Millis(_time - oth._time)

    def compare(oth: Time): Int = _time.compare(oth._time)
    override def toString = s"Time(${_time})"
  }

  object Millis {
    val MinValue = new Millis(Long.MinValue)
    val MaxValue = new Millis(Long.MaxValue)
    val Zero     = new Millis(0L)
  }

  object Time {
    /**
     * @return the current time
     */
    def now: Time = new Time(System.currentTimeMillis())
  }

  /**
   * The duration of a simulated day
   */
  val dayDuration: Millis = {
    val conf = ConfigFactory.load()
    val globalConfig = conf.getConfig("infra.global")
    new Millis(globalConfig.getMilliseconds("dayDuration"))
  }

  /**
   * The timestamp of the zero instant
   */
  val zero: Time = sys.env.get("zero").map(z => new Time(z.toLong * 1000)).getOrElse{
    println("No environment variable zero found, using current time.")
    Time.now}

  /**
   * @return the elapsed time since `zero`
   */
  def timeElapsed: Millis = Time.now - zero

  /**
   * @return the time elapsed since the last midnight
   */
  def timeOfDay = new DayTime(timeElapsed._millis % dayDuration._millis)


  /**
   * @return the next [[Time]] the specified [[DayTime]] will happen
   */
  def nextDayTime(dayTime: DayTime, now: Time = Time.now): Time = {
    val wait = new Millis((dayDuration._millis + dayTime._time -
      ((now - zero)._millis % dayDuration._millis))
      % dayDuration._millis)

    assert(wait._millis >= 0)
    now + wait
  }

  /**
   * @return the relative time ([[Millis]]) until the specified [[Time]]
   */
  def waitUntil(until: Time, from: Time = Time.now) : Millis = until - from

  /**
   * @return the end and duration of a sleep with a specified duration
   */
  def waitFor(wait: Millis, from: Time = Time.now): (Time, Millis) = {
    val end = from + wait
    (end, new Millis(max(1, (end - Time.now)._millis)))
  }

  /**
   * Creates a DayTime from configuration
   * @param conf configuration parameters
   * @return the time created
   */
  def dayTimeFromConfiguration(conf: Configuration) = {
    val time = round(floor(conf.getDouble("time").get / 24.0 * dayDuration._millis))
    assert(time < dayDuration._millis, s"$time > ${dayDuration._millis}")
    new DayTime(time)
  }

  /**
   * Converts Millis to JSON
   */
  implicit val millisWrites = new Writes[Millis] {
    def writes(m: Millis) =
      JsNumber(m._millis)
  }

  /**
   * Converts Time to JSON
   */
  implicit val timeWrites = new Writes[Time] {
    def writes(t: Time) =
      JsNumber(t._time)
  }

  def init() = Unit
}
