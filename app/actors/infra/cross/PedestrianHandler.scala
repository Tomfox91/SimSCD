package actors.infra.cross

import actors.infra.cross.PedestrianHandler._
import actors.infra.position
import actors.infra.position.PedestrianPosition._
import actors.infra.position._
import controllers.Timing.{Millis, Time}
import things.Pedestrian

import scala.collection.mutable.ListBuffer
import scala.collection.immutable

object PedestrianHandler {
  private[cross] val pedestrianCrossingsForRoad:
    Map[ConnectionPosition, Seq[(PedestrianPosition, PedestrianPosition)]] =
    ((for (ns <- NorthSouth.allNSs) yield Seq(ns -> Seq(
      (PedestrianPosition(ns, East), PedestrianPosition(ns, West)),
      (PedestrianPosition(ns, West), PedestrianPosition(ns, East))))).flatten ++
    (for (we <- WestEast.allWEs) yield Seq(we -> Seq(
      (PedestrianPosition(North, we), PedestrianPosition(South, we)),
      (PedestrianPosition(South, we), PedestrianPosition(North, we))))).flatten).toMap
}


/**
 * Handles forwarding decisions for  pedestrians, only considering precedence and priority levels.
 * @param roadCrossingTime the time used to cross the road
 */
class PedestrianHandler private[cross] (roadCrossingTime: Millis, context: HandlerContext,
                                        guardsState: GuardStateForPedestrians) {
  import context._
  import guardsState._

  private case class PendingPedestrian(pedestrian: Pedestrian,
                                       dest: (PedestrianPosition, ConnectionPosition)) {
    override def toString = s"PenPed(${pedestrian.id})"
  }
  
  private val pendingPedestrians:
  Map[(PedestrianPosition, PedestrianPosition), ListBuffer[PendingPedestrian]] =
    (for (ns <- NorthSouth.allNSs; we <- WestEast.allWEs; from = PedestrianPosition(ns, we);
          to <- Seq(PedestrianPosition(ns.opposite, we), PedestrianPosition(ns, we.opposite)))
      yield (from, to) -> new ListBuffer[PendingPedestrian]()).toMap

  private def handlePendingPedestrian(pPed: PendingPedestrian, current: PedestrianPosition, t: Time) {

    (current.ns == pPed.dest._1.ns, current.we == pPed.dest._1.we) match {
      case (true, true) => // he is where he wants to be, send it now
        forwardPedestrian(pPed.pedestrian, to = PPDtoPCP((pPed.dest._1, pPed.dest._2)), time = t)

      case (true, false) =>
        pendingPedestrians(current -> pPed.dest._1) += pPed
        currentPedestriansWaiting(current.ns) = true

      case (false, true) =>
        pendingPedestrians(current -> pPed.dest._1) += pPed
        currentPedestriansWaiting(current.we) = true

      case (false, false) =>
        pendingPedestrians(current -> PedestrianPosition(current.ns.opposite, current.we)) += pPed
        currentPedestriansWaiting(current.we) = true
    }

    tryForward(t)
  }

  /**
   * Adds the pedestrian to the pending pedestrians structure or forwards it immediately (if possible)
   * @param from the [[position.PedestrianConnectionPosition]] of the sidewalk the pedestrian comes from
   * @param pedestrian the pedestrian
   * @param to the turn the pedestrian wants to take
   */
  def acceptPedestrian(from: PedestrianConnectionPosition, pedestrian: Pedestrian,
                       to: PedestrianConnectionPosition, t: Time) {
    log.info(s"PH: received pedestrian ${pedestrian.id} to $to")

    sendThingEnteredEvent(pedestrian, Millis.Zero, vertex = Some(PCPtoPPD(from)._1))
    handlePendingPedestrian(
      PendingPedestrian(pedestrian = pedestrian, dest = PCPtoPPD(to)),
      current = PCPtoPPD(from)._1, t = t)
  }

  /**
   * Selects pedestrians that may cross the road in the current tick, and closes
   * the guards of the crossed roads.
   */
  def tryForward(t: Time): Unit = {
    for (
      // for every road
      cp <- ConnectionPosition.allCPs
      // if pedestrians on that road may pass
      if pedestrianMayPass(cp);
      // for both pedestrian crossings on that road
      pCross <- pedestrianCrossingsForRoad(cp)
      if pendingPedestrians(pCross).nonEmpty
    ) {
      pedestrianCross(pPds = pendingPedestrians(pCross).toList, crossedRoad = cp, fromTo = pCross, t = t)
      pendingPedestrians(pCross).clear()
      currentPedestriansWaiting(cp) = false
    }
  }

  private case class TransitingPedestrians(pPds: immutable.Iterable[PendingPedestrian],
                                           crossedRoad: ConnectionPosition,
                                           to: PedestrianPosition)

  private def pedestrianCross(pPds: immutable.Iterable[PendingPedestrian],
                              crossedRoad: ConnectionPosition,
                              fromTo: (PedestrianPosition, PedestrianPosition), t: Time) {
    currentPedestrianTransits(crossedRoad) += pPds.size
    requestPedestrianTransitTimeout(t + roadCrossingTime,
      TransitingPedestrians(pPds = pPds, crossedRoad = crossedRoad, to = fromTo._2))
    pPds.foreach(pPed =>
      sendThingEnteredEvent(pPed.pedestrian, roadCrossingTime, vertex = Some(fromTo._2)))
  }

  /**
   * Called by the crossing when a timeout ends.
   */
  def transitTimeout(exp: Time, reference: AnyRef) = reference match {
    case TransitingPedestrians(pPds, crossedRoad, to) =>
      currentPedestrianTransits(crossedRoad) -= pPds.size

      pPds.foreach(pPed => handlePendingPedestrian(pPed = pPed, current = to, t = exp))

    case x => throw new IllegalArgumentException(x.toString)
  }

  override def toString = s"pendingPedestrians = ${pendingPedestrians.filter(_._2.nonEmpty)}\n"
}
