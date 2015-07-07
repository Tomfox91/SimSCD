package actors

import actors.infra.City
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

/**
 * Contains the actor system.
 */
object System {
  val config = ConfigFactory.load

  /**
   * The actor system.
   */
  val infraSystem = {
    if (config.hasPath("node"))
      ActorSystem(name = "infra", config = config.getConfig("node.play.infra").withFallback(config))
    else
      ActorSystem(name = "infra", config = config)
  }

  /**
   * Starts the city.
   */
  def init() = {
    infraSystem.actorOf(Props[City], "city")
  }
}
