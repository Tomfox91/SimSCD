import actors.System
import controllers.Timing
import play.api.{Application, GlobalSettings, Logger}

/**
 * Global object, used to start the application.
 */
object Global extends GlobalSettings {
  /**
   * Starts the application, by initializing [[controllers.Timing]] and [[actors.System]].
   */
  override def onStart(app: Application) {
    Logger.info("Global init")
    Timing.init()
    System.init()
  }
}