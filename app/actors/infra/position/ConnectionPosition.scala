package actors.infra.position

import akka.actor.ActorRef

/** Represents the result of comparisons between two directions. */
sealed trait RelativeDirection

/** Relative direction: right */
case object RDRight    extends RelativeDirection
/** Relative direction: straight */
case object RDStraight extends RelativeDirection
/** Relative direction: left */
case object RDLeft     extends RelativeDirection
/** Relative direction: U-turn */
case object RDUTurn    extends RelativeDirection

/** Represents a turn to be taken */
sealed trait TurnRef

/**
 * Represents a particular road attachment point of a crossing:
 * [[North]], [[East]], [[South]] or [[West]]
 */
sealed trait ConnectionPosition extends TurnRef {
  /** @return the direction turning right coming from this one */
  def right: ConnectionPosition
  /** @return the direction going straight coming from this one */
  def straight = right.right
  /** @return the direction turning left coming from this one */
  def left = straight.right
  /** @return the direction performing an U-turn coming from this one */
  def uTurn = this

  def toChar: Char

  /**
   * Compares this direction with another
   */
  val compareTo: ConnectionPosition => RelativeDirection = {
    case cp if cp == right => RDRight
    case cp if cp == straight => RDStraight
    case cp if cp == left => RDLeft
    case cp if cp == uTurn => RDUTurn
  }
}

/** [[North]] or [[South]] */
sealed trait NorthSouth extends ConnectionPosition {
  /** @return the opposite direction */
  def opposite: NorthSouth
}

/** [[West]] or [[East]] */
sealed trait WestEast extends ConnectionPosition {
  /** @return the opposite direction */
  def opposite: WestEast
}

case object North extends NorthSouth {
  override def right: ConnectionPosition = West
  override def opposite: NorthSouth = South
  def toChar = 'N'
}

case object East extends WestEast {
  override def right: ConnectionPosition = North
  override def opposite: WestEast = West
  def toChar = 'E'
}

case object South extends NorthSouth {
  override def right: ConnectionPosition = East
  override def opposite: NorthSouth = North
  def toChar = 'S'
}

case object West extends WestEast {
  override def right: ConnectionPosition = South
  override def opposite: WestEast = East
  def toChar = 'W'
}



object ConnectionPosition {
  /** Converts from a Char to a [[ConnectionPosition]]. */
  def apply(str: Char) = str match {
    case 'N' => North
    case 'E' => East
    case 'S' => South
    case 'W' => West
  }

  /** @return all [[ConnectionPosition]]s. */
  val allCPs: Seq[ConnectionPosition] = Seq(North, East, South, West)
}

object NorthSouth {
  /** @return `Seq(North, South)`. */
  val allNSs: Seq[NorthSouth] = Seq(North, South)
}

object WestEast {
  /** @return `Seq(West, East)`. */
  val allWEs: Seq[WestEast] = Seq(West, East)
}



/** A side of the road. */
trait Side {
  /** @return the other side */
  def other: Side
}

/** The left side of the road. */
case object LeftS extends Side {
  def other = RightS
}

/** The right side of the road. */
case object RightS extends Side {
  def other = LeftS
}

object Side {
  /** @return `Seq(LeftS, RightS)`. */
  val allSides: Seq[Side] = Seq(LeftS, RightS)
}



/** A [[ConnectionPosition]] with a [[Side]]. */
sealed case class PedestrianConnectionPosition(position: ConnectionPosition,
                                               side: Side,
                                               useBusLink: Option[ActorRef] = None
                                                ) extends TurnRef {
  override def toString =
    s"PCP($position,$side${useBusLink.fold("")(",TB" + _.hashCode()%100)})"
}

/** A position of a pedestrian inside a Crossing. */
sealed case class PedestrianPosition(ns: NorthSouth, we: WestEast)

object PedestrianPosition {
  private val PCPtoPPDMap:
  Map[PedestrianConnectionPosition, (PedestrianPosition, ConnectionPosition)] =
    (for (cp <- ConnectionPosition.allCPs; s <- Side.allSides)
    yield PedestrianConnectionPosition(cp, s) -> ((cp, s) match {
        case (ns: NorthSouth, LeftS)  => PedestrianPosition(ns, ns.right.asInstanceOf[WestEast])
        case (ns: NorthSouth, RightS) => PedestrianPosition(ns, ns.left.asInstanceOf[WestEast])
        case (we: WestEast,   LeftS)  => PedestrianPosition(we.right.asInstanceOf[NorthSouth], we)
        case (we: WestEast,   RightS) => PedestrianPosition(we.left.asInstanceOf[NorthSouth], we)
        case x => throw new MatchError(x)
      }, cp)).toMap

  /** Converts [[PedestrianConnectionPosition]] to
    *  [[PedestrianPosition]] and [[ConnectionPosition]]. */
  def PCPtoPPD(pcp: PedestrianConnectionPosition) =
    PCPtoPPDMap(PedestrianConnectionPosition(pcp.position, pcp.side))


  /** Converts [[PedestrianPosition]] and [[ConnectionPosition]]
    *  to [[PedestrianConnectionPosition]] */
  val PPDtoPCP:
  Map[(PedestrianPosition, ConnectionPosition), PedestrianConnectionPosition] =
    (for (ns <- NorthSouth.allNSs; we <- WestEast.allWEs; dir <- Seq(ns, we))
    yield ((PedestrianPosition(ns, we), dir),
        PedestrianConnectionPosition(dir, {
          val (l, r) = (dir.right, dir.left)
          Seq(ns, we).filterNot(_ == dir).head match {
            case `l` => LeftS
            case `r` => RightS
            case x => throw new MatchError(x)
          }
        }))).toMap

  /** @return Manhattan distance between two [[PedestrianPosition]]. */
  def pedPosDiff(a: PedestrianPosition, b: PedestrianPosition) = {
    (if (a.ns != b.ns) 1 else 0) +
      (if (a.we != b.we) 1 else 0)
  }
}



/**
 * Contains various utilities to manage trajectories.
 */
object TrajectoryCmp {

  private sealed trait AB
  private object A extends AB
  private object B extends AB

  /**
   *  A trajectory.
   * @param from the start of the trajectory
   * @param to the destination of the trajectory
   */
  sealed case class Trajectory(from: ConnectionPosition, to: ConnectionPosition)

  /** [[NotColliding]] or [[Colliding]] */
  private sealed trait TrajectoryRelation
  /** Represents the fact that two trajectories do not collide. */
  private case object NotColliding extends TrajectoryRelation
  /** Represents the fact that two trajectories do collide.
   * @param precedence the trajectory that has precedence */
  private sealed case class Colliding(precedence: AB) extends TrajectoryRelation


  /**
   * Compares two trajectories.
   * The map contains [[Colliding]] or [[NotColliding]] for each possible pair of [[Trajectory]]es.
   */
  private val compareTrajectories: Map[(Trajectory, Trajectory), TrajectoryRelation] = {
    def allTrajectories =
      for (from <- Seq(North, East, South, West);
           to   <- Seq(North, East, South, West))
      yield Trajectory(from, to)

    def compute(a: Trajectory, b: Trajectory): TrajectoryRelation = {
      a.from.compareTo(a.to) match {
        case RDRight => // A : ↱
          if (b.to == a.to)
            Colliding(precedence = A)
          else
            NotColliding

        case RDStraight => // A : ↑
          a.from.compareTo(b.from) match {
            case RDRight =>
              if (b.from == b.to)
                NotColliding
              else
                Colliding(precedence = B)

            case RDStraight =>
              a.from.compareTo(b.to) match {
                case (RDUTurn | RDLeft) =>
                  NotColliding
                case (RDRight | RDStraight) =>
                  Colliding(precedence = A)
              }

            case RDLeft =>
              a.from.compareTo(b.to) match {
                case (RDUTurn | RDLeft) =>
                  NotColliding
                case (RDRight | RDStraight) =>
                  Colliding(precedence = A)
              }

            case x => throw new MatchError(x)
          }

        case RDLeft => // A : ↰
          a.from.compareTo(b.from) match {
            case RDRight =>
              a.from.compareTo(b.to) match {
                case (RDRight | RDStraight) =>
                  NotColliding
                case (RDLeft | RDUTurn) =>
                  Colliding(precedence = B)
              }

            case RDStraight =>
              a.from.compareTo(b.to) match {
                case (RDRight | RDStraight) =>
                  NotColliding
                case (RDLeft | RDUTurn) =>
                  Colliding(precedence = B)
              }

            case RDLeft =>
              a.from.compareTo(b.to) match {
                case (RDUTurn) =>
                  NotColliding
                case (RDRight | RDStraight | RDLeft) =>
                  Colliding(precedence = A)
              }

            case x => throw new MatchError(x)
          }

        case RDUTurn => // A: ↶
          if (b.to != a.from)
            NotColliding
          else
            Colliding(precedence = B)
      }
    }

    for (a <- allTrajectories; b <- allTrajectories; if a.from != b.from)
      yield (a, b) -> compute(a, b)
  }.toMap

  /**
   * Returns true iff trajectory `a` has precedence over trajectory `b`.
   */
  def hasPrecedence(a: Trajectory, b: Trajectory): Boolean = {
    assert(a.from != b.from, s"Comparing $a with $b")

    compareTrajectories(a, b) match {
      case NotColliding => true
      case Colliding(A) => true
      case Colliding(B) => false
    }
  }

  /**
   * Returns true iff trajectory `a` and trajectory `b` do not collide.
   */
  def doNotCollide(a: Trajectory, b: Trajectory): Boolean = {
    assert(a.from != b.from, s"Comparing $a with $b")

    compareTrajectories(a, b) match {
      case NotColliding => true
      case Colliding(A) => false
      case Colliding(B) => false
    }
  }
}
