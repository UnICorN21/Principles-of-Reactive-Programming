package kvstore

import akka.actor._
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ ask, pipe }
import scala.collection.mutable.{ Map, HashMap, MultiMap, Set }
import scala.concurrent.duration._
import akka.util.Timeout

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */
  
  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  var replicationAcks = Map.empty[Long, (ActorRef, Int)]
  var replicatorAcks = new HashMap[ActorRef, Set[Long]] with MultiMap[ActorRef, Long]
  var persistAcks = Map.empty[Long, (ActorRef, Cancellable)]

  var expectedSeq = 0L

  val persistor = context.actorOf(persistenceProps)
  arbiter ! Join

  def receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    case Insert(key, value, id) =>
      kv += key -> value
      if (replicators.nonEmpty) {
        replicationAcks += id -> (sender, replicators.size)
        replicators foreach { r =>
            replicatorAcks.addBinding(r, id)
            r ! Replicate(key, Some(value), id)
        }
      }
      val cancellable = context.system.scheduler.schedule(0.seconds, 100.milliseconds, persistor, Persist(key, Some(value), id))
      persistAcks += id -> (sender, cancellable)
      context.system.scheduler.scheduleOnce(1.seconds) {
        persistAcks get id match {
          case Some((s, cancellable)) =>
            cancellable.cancel
            persistAcks -= id
            s ! OperationFailed(id)
          case None =>
            replicationAcks get id match {
              case Some((s, _)) =>
                replicationAcks -= id
                s ! OperationFailed(id)
              case None =>
            }
        }
      }
    case Remove(key, id) =>
      kv -= key
      if (replicators.nonEmpty) {
        replicationAcks += id -> (sender, replicators.size)
        replicators foreach { r =>
          replicatorAcks.addBinding(r, id)
          r ! Replicate(key, None, id)
        }
      }
      val cancellable = context.system.scheduler.schedule(0.seconds, 100.milliseconds, persistor, Persist(key, None, id))
      persistAcks += id -> (sender, cancellable)
      context.system.scheduler.scheduleOnce(1.seconds) {
        persistAcks get id match {
          case Some((s, cancellable)) =>
            cancellable.cancel
            persistAcks -= id
            s ! OperationFailed(id)
          case None =>
            replicationAcks get id match {
              case Some((s, _)) =>
                replicationAcks -= id
                s ! OperationFailed(id)
              case None =>
            }
        }
      }
    case Get(key, id) =>
      sender ! GetResult(key, kv get key, id)
    case Persisted(key, id) =>
      persistAcks get id match {
        case Some((s, cancellable)) =>
          cancellable.cancel
          persistAcks -= id
          if (!replicationAcks.contains(id))
            s ! OperationAck(id)
        case None =>
      }
    case Replicated(key, id) =>
      replicatorAcks get sender match {
        case Some(s) => s -= id
        case None =>
      }
      replicationAcks get id match {
        case Some((s, rest)) =>
          val updated = rest - 1
          if (0 == updated) {
            replicationAcks -= id
            if (!persistAcks.contains(id))
              s ! OperationAck(id)
          } else {
            replicationAcks += id -> (s, updated)
          }
        case None =>
      }
    case Replicas(replicas) => {

      val secs = replicas - self
      val newJoiners = secs -- secondaries.keySet
      val leavers =  secondaries.keySet -- secs

      newJoiners foreach { nj =>
        val r = context.system.actorOf(Replicator.props(nj))
        secondaries += nj -> r
        replicators += r
        var counter = 0
        kv foreach { e =>
          r ! Replicate(e._1, Some(e._2), counter)
          counter += 1
        }
      }

      leavers foreach { l =>
        secondaries get l match {
          case Some(r) => {
            context.stop(r)
            secondaries -= l
            replicators -= r

            replicatorAcks get r match {
              case Some(outstandingAcks) => {
                outstandingAcks foreach { a =>
                  self ! Replicated("", a)
                }
                replicatorAcks -= r
              }
              case None =>
            }
          }
          case None =>
        }
      }
    }
  }

  /* TODO Behavior for the replica role. */
  val replica: Receive = {
    case Snapshot(key, valueOpt, seq) =>
      if (seq < expectedSeq) sender ! SnapshotAck(key, seq)
      else if (seq > expectedSeq) { /* null */ }
      else {
        valueOpt match {
          case Some(value) => kv += key -> value
          case None => kv -= key
        }
        val cancellable = context.system.scheduler.schedule(0.seconds, 100.milliseconds, persistor, Persist(key, valueOpt, seq))
        persistAcks += seq -> (sender, cancellable)
        expectedSeq += 1
      }
    case Get(key, id) =>
      sender ! GetResult(key, kv get key, id)
    case Persisted(key, id) =>
      persistAcks get id match {
        case Some((replicator, cancellable)) =>
          cancellable.cancel
          persistAcks -= id
          replicator ! SnapshotAck(key, id)
        case None =>
      }
  }

}

