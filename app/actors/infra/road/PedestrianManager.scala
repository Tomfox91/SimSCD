package actors.infra.road

import actors.infra.PedestrianToSidewalk
import actors.infra.position.{PedestrianConnectionPosition, TurnRef}
import actors.infra.road.Building.PersonArriving
import actors.infra.road.Navigator.{Arrived, Next, NextTurn}
import actors.infra.road.ParkingLot.PersonReturning
import actors.infra.road.PedestrianManager.PedestrianTakingBus
import actors.infra.road.Road.{RoadId, RoutableId}
import actors.notifications.EventBus.{Event, ThingEnteredStructure}
import akka.actor.ActorRef
import akka.event.LoggingAdapter
import controllers.Timing.{Millis, Time}
import things.Bus.BusLine
import things._

import scala.collection.mutable.ListBuffer
import scala.math.min

object PedestrianManager {
  sealed case class PedestrianTakingBus(pedestrian: Pedestrian, bus: Bus) extends Event
}

/**
 * Manages pedestrians on behalf of a road.
 * @param routeBus a function to be invoked when a bus has been processed and must be forwarded
 * @param road identifier of the road
 * @param parkingLot reference to the [[ParkingLot]]
 * @param building reference to the [[Building]]
 * @param sidewalkForward reference to the `sidewalkFroward`
 * @param sidewalkReverse reference to the `sidewalkReverse`
 * @param sidewalkTime time needed to go through the [[Sidewalk]]
 * @param pedNavigatorForward [[Navigator]] for pedestrian forward routes
 * @param pedNavigatorReverse [[Navigator]] for pedestrian reverse routes
 * @param busPedNavigatorForward [[Navigator]] for pedestrian or bus forward routes
 * @param busPedNavigatorReverse [[Navigator]] for pedestrian or bus reverse routes
 * @param busNavigators collection of [[Navigator]]s for bus routes
 * @param actor reference to the [[Road]]
 * @param log event logger
 * @param eventBus reference to the [[actors.notifications.EventBus]] for this Area
 */
class PedestrianManager private[road] (routeBus: (Bus, Time) => Unit, road: RoadId,
                                       parkingLot: ActorRef, building: ActorRef,
                                       sidewalkForward: ActorRef, sidewalkReverse: ActorRef,
                                       sidewalkTime: Millis,
                                       pedNavigatorForward: Navigator[PedestrianConnectionPosition],
                                       pedNavigatorReverse: Navigator[PedestrianConnectionPosition],
                                       busPedNavigatorForward: Navigator[PedestrianConnectionPosition],
                                       busPedNavigatorReverse: Navigator[PedestrianConnectionPosition],
                                       busNavigators: Map[BusLine, Navigator[PedestrianConnectionPosition]],
                                       actor: ActorRef, log: LoggingAdapter, eventBus: ActorRef) {

  /**
   * Sends a person to the parkingLot to park his car
   * @param pwc person to send to the parkingLot
   */
  private def sendToParkingLot(pwc: PersonWithCar, t: Time) {
    parkingLot.!(PersonReturning(pwc, t))(actor)
  }

  /**
   * Send a person to the building
   * @param person person to send to the building
   */
  def sendToBuilding(person: Person) = {
    building.!(PersonArriving(person))(actor)
  }


  private val busLines = busNavigators.keys

  private val waitingPedestrians: Map[BusLine, ListBuffer[Pedestrian]] =
    busLines.map(_ -> ListBuffer[Pedestrian]()).toMap

  private var hereBus: Option[BusLine] = None
  private val remainingInBus = ListBuffer[Pedestrian]()

  /**
   * Called when a bus is going through the road
   * @param bus bus arrived from crossing
   */
  def receiveBus(bus: Bus, timestamp: Time) {
    log.info(s"bus ${bus.id}, line ${bus.line}")
    routeBus(decidePassengers(bus, timestamp), timestamp)
  }

  private def bestNavigator[TR <: TurnRef, K](navigatorKeys: Seq[(Navigator[TR], K)],
                                              destination: RoutableId): (K, Next[TR], Millis) =
    (for ((navigator, key) <- navigatorKeys;
          next = navigator.getDirection(destination);
          met = next match {
            case Arrived => Millis.MinValue
            case nt: NextTurn[TR] => nt.metric
          })
    yield (key, next, met))
      .minBy(_._3)

  private type PCP = PedestrianConnectionPosition
  private type Forwarder = (Navigator[PCP], (Pedestrian, PCP, Time) => Any)

  private def waitBus(line: BusLine, pedestrian: Pedestrian) {
    hereBus match {
      case Some(`line`) =>
        remainingInBus += pedestrian

      case _ =>
        log.info(s"Pedestrian ${pedestrian.id} waiting for bus line $line")
        eventBus.!(ThingEnteredStructure(pedestrian, "busStop"))(actor)
        waitingPedestrians(line) += pedestrian
    }
  }

  private val pedForwarderForward: Forwarder =
    (pedNavigatorForward,
      (p: Pedestrian, nd: PCP, t: Time) =>
        sidewalkForward ! PedestrianToSidewalk(p, nd, t))
  private val busPedForwarderForward: Forwarder =
    (busPedNavigatorForward,
      (p: Pedestrian, nd: PCP, t: Time) =>
        sidewalkForward ! PedestrianToSidewalk(p, nd, t))

  private val pedForwarderReverse: Forwarder =
    (pedNavigatorReverse,
      (p: Pedestrian, nd: PCP, t: Time) =>
        sidewalkReverse ! PedestrianToSidewalk(p, nd, t))
  private val busPedForwarderReverse: Forwarder =
    (busPedNavigatorReverse,
      (p: Pedestrian, nd: PCP, t: Time) =>
        sidewalkReverse ! PedestrianToSidewalk(p, nd, t))

  private val pedForwarders: Seq[Forwarder] = Seq(pedForwarderForward,
    (pedNavigatorReverse,
      (p: Pedestrian, nd: PCP, t: Time) =>
        sidewalkReverse ! PedestrianToSidewalk(p, nd, t, shortcut = true)))

  private def busPedForwardersForward: Seq[Forwarder] =
    busPedForwarderForward +:
      (for ((line, nav) <- busNavigators) yield (nav,
        (p: Pedestrian, nd: Any, t: Time) =>
          waitBus(line, p)
        )).toSeq

  private def busPedForwardersAll: Seq[Forwarder] = busPedForwardersForward :+
    ((busPedNavigatorReverse,
      (p: Pedestrian, nd: PCP, t: Time) =>
        sidewalkReverse ! PedestrianToSidewalk(p, nd, t, shortcut = true)))

  private def genericForward(pedestrian: Pedestrian, t: Time, forwarders: Seq[Forwarder]) {
    val (forward: ((Pedestrian, PCP, Time) => Any), next: Next[PCP], _) =
      bestNavigator(forwarders, pedestrian.destination)

    next match {
      case Arrived =>

        pedestrian match {
          case Pedestrian(pwc@PersonWithCar(_, _, _, Some(`road`)), Some(`road`)) =>
            // pedestrian returning to parking lot after going somewhere
            sendToParkingLot(pwc, t)
          case _ =>
            sendToBuilding(pedestrian.person)
        }

      case nt: NextTurn[PCP] =>
        forward(pedestrian, nt.turn, t)
    }
  }

  /**
   * Forwards a pedestrian in the forward direction
   * @param pedestrian pedestrian to forward
   */
  def forwardPedestrianForward(pedestrian: Pedestrian, t: Time) {
    genericForward(pedestrian, t, pedestrian.person match {
      case _:PersonWithoutCar => busPedForwardersForward
      case _:PersonWithCar    => Seq(pedForwarderForward)
    })
  }

  /**
   * Forwards a pedestrian in the reverse direction
   * @param pedestrian pedestrian to forward
   */
  def forwardPedestrianReverse(pedestrian: Pedestrian, t: Time) {
    genericForward(pedestrian, t, pedestrian.person match {
      case _:PersonWithoutCar => Seq(busPedForwarderReverse)
      case _:PersonWithCar    => Seq(pedForwarderReverse)
    })
  }

  /**
   * Forwards a pedestrian in the best direction
   * @param pedestrian pedestrian to forward
   */
  def forwardBidirectionalPedestrian(pedestrian: Pedestrian, t: Time) {
    genericForward(pedestrian, t, pedestrian.person match {
      case _:PersonWithoutCar => busPedForwardersAll
      case _:PersonWithCar    => pedForwarders
    })
  }

  /**
   * Extracts all passengers from the bus and routes each of them, then
   * returns the bus with the remaining passengers.
   * @param bus the bus
   */
  private def decidePassengers(bus: Bus, t: Time): Bus = {
    hereBus = Some(bus.line)
    remainingInBus.clear()

    bus.passengers.foreach(forwardBidirectionalPedestrian(_, t))

    val remainingCount = remainingInBus.size
    val enteringCount = min(
      waitingPedestrians(bus.line).size,
      bus.capacity - remainingCount)
    val enteringPassengers = waitingPedestrians(bus.line).take(enteringCount)
    waitingPedestrians(bus.line).trimStart(enteringCount)

    bus.passengers diff remainingInBus foreach {ped =>
      log.info(s"Pedestrian ${ped.id} exiting bus ${bus.id} line ${bus.line}")
    }
    enteringPassengers foreach {ped =>
      log.info(s"Pedestrian ${ped.id} entering bus ${bus.id} line ${bus.line}")
      eventBus.!(PedestrianTakingBus(ped, bus))(actor)
    }

    hereBus = None
    bus.withPassengers(remainingInBus ++ enteringPassengers)
  }

  /**
   * Forwards a person to the next destination or the park where their car is, depending on the distance.
   * @param person the person
   * @param fromPark whether the person is exiting from the ParkingLot or not
   */
  private def forwardBidirectionalPersonToParkOrNextDestination(person: Person, t: Time, fromPark: Boolean) {
    person match {
      case pwo: PersonWithoutCar =>
        forwardBidirectionalPedestrian(Pedestrian(pwo), t)

      case pwc: PersonWithCar =>
        val dest = pwc.destination
        val park = pwc.carParkedIn.get

        val (forwardD, nextD, metD) = bestNavigator(pedForwarders, dest)
        val (forwardP, nextP, metP) = bestNavigator(pedForwarders, park)

        (nextD, nextP) match {
          case (Arrived, _) =>
            sendToBuilding(pwc)

          case (_, Arrived) if !fromPark =>
            sendToParkingLot(pwc, t)

          case (ntD: NextTurn[PCP], _) if fromPark =>
            forwardD(Pedestrian(pwc), ntD.turn, t)

          case (ntD: NextTurn[PCP], ntP: NextTurn[PCP]) =>
            if (metD <= metP * 2) // it's better to walk to the next destination
              forwardD(Pedestrian(pwc), ntD.turn, t)
            else // it's better to get back to the car
              forwardP(Pedestrian(pwc, destinationOverride = Some(park)), ntP.turn, t)
        }
    }
  }

  /**
   * Forwards a person who arrives from the parkingLot
   * @param person person to forward
   */
  def forwardPersonFromParking(person: PersonWithCar, timestamp: Time) {
    forwardBidirectionalPersonToParkOrNextDestination(person, timestamp, fromPark = true)
  }

  /**
   * Forwards a person who arrives from the building
   * @param person person to forward
   */
  def forwardPersonFormBuilding(person: Person, timestamp: Time) {
    forwardBidirectionalPersonToParkOrNextDestination(person, timestamp, fromPark = false)
  }

  /**
   * @return the pedestrians currently waiting for a bus
   */
  def getPedestrians = waitingPedestrians.values.reduce(_ ++ _)
}
