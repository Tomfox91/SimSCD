package actors.infra.road

import actors.infra.RoutingTableUpdate
import actors.infra.position.TurnRef
import actors.infra.road.Navigator.{Arrived, Next, NextTurn}
import actors.infra.road.Road._
import actors.infra.road.RoadNavigator.routeToAreaTax
import com.typesafe.config.ConfigFactory
import controllers.Timing.Millis

import scala.collection.mutable

object Navigator {
  sealed trait Next[+TR <: TurnRef]
  object Arrived extends Next[Nothing]

  object NextTurn {
    def apply[TR <: TurnRef](t: (TR, Millis)): NextTurn[TR] = NextTurn[TR](t._1, t._2)
  }
  sealed case class NextTurn[+TR <: TurnRef](turn: TR, metric: Millis) extends Next[TR]
}

sealed trait Navigator[TR <: TurnRef] {
  def update(data: RoutingTableUpdate[TR])
  def getDirection(destination: RoutableId): Next[TR]
}

object RoadNavigator {
  private val routeToAreaTax =
    new Millis(ConfigFactory.load.getMilliseconds("infra.global.routingAgent.maxDistance"))
}

/**
 * Contains routing data for a road and uses the data to route [[things.RoadThing]]s.
 * @param owner road that owns the navigator
 * @tparam TR the type of the routing information
 *            (usually [[actors.infra.position.ConnectionPosition]]
 *            or [[actors.infra.position.PedestrianConnectionPosition]])
 */
sealed class RoadNavigator[TR <: TurnRef] private[road] (owner: RoadId) extends Navigator[TR] {
  /**
   * For each destination, contains the next turn and the metric
   */
  private val table = mutable.Map[RoutingId, (TR, Millis)]()
  private var additionalInfo = Set[AdditionalRoutingInformation]()

  /**
   * Updates the routing table with new data from a [[RoadRoutingAgent]].
   * @param data the updated data
   */
  def update(data: RoutingTableUpdate[TR]) {
    table ++= data.data
    additionalInfo = data.additionalInfo
  }

  /**
   * Returns a [[Navigator.NextTurn]] containing the next turn to take, or
   * [[Navigator.Arrived]] if the road is the destination.
   * @param destination the required destination
   */
  def getDirection(destination: RoutableId): Next[TR] = {
    destination match {
      case ari: AdditionalRoutingInformation if additionalInfo.contains(ari) => Arrived
      case ari: AdditionalRoutingInformation => table.get(ari).map(NextTurn.apply[TR]).get
      case ri: RoadId if ri == owner => Arrived
      case ri: RoadId => table.get(ri)
        // if we only have a route for the area we add a tax to prefer complete routes
        .orElse(table.get(ri.containingArea).map { case (tr, m) => (tr, m + routeToAreaTax) })
        .map(NextTurn.apply[TR]).get
    }
  }

  def printState() {
    println("additionalInfo = " + additionalInfo)
    println("table = " + table)
  }
}
