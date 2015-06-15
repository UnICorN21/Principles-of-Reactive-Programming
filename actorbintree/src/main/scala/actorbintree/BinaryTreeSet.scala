/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply
  
  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case msg: Operation => root ! msg
    case GC =>
      val newRoot = createRoot
      context.become(garbageCollecting(newRoot))
      root ! CopyTo(newRoot)
  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case msg: Operation => pendingQueue = pendingQueue.enqueue(msg)
    case CopyFinished =>
      while (!pendingQueue.isEmpty) {
        val (operation, queue) = pendingQueue.dequeue
        newRoot ! operation
        pendingQueue = queue
      }
      root ! PoisonPill
      root = newRoot
      context.become(normal)
    case GC =>
  }

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case msg @ Insert(requester, id, elem) =>
      if (elem == this.elem) {
        this.removed = false
        requester ! OperationFinished(id)
      } else if (elem < this.elem) {
        subtrees.get(Left) match {
          case Some(left) => left ! msg
          case None =>
            subtrees = subtrees + (Left -> context.actorOf(BinaryTreeNode.props(elem, false)))
            requester ! OperationFinished(id)
        }
      } else {
        subtrees.get(Right) match {
          case Some(right) => right ! msg
          case None =>
            subtrees = subtrees + (Right -> context.actorOf(BinaryTreeNode.props(elem, false)))
            requester ! OperationFinished(id)
        }
      }
    case msg @ Remove(requester, id, elem) =>
      if (elem == this.elem) {
        this.removed = true
        requester ! OperationFinished(id)
      } else if (elem < this.elem) {
        subtrees.get(Left) match {
          case Some(left) => left ! msg
          case None => requester ! OperationFinished(id)
        }
      } else {
        subtrees.get(Right) match {
          case Some(right) => right ! msg
          case None => requester ! OperationFinished(id)
        }
      }
    case msg @ Contains(requester, id, elem) =>
      if (elem == this.elem) {
        requester ! ContainsResult(id, !removed)
      } else if (elem < this.elem) {
        subtrees.get(Left) match {
          case Some(left) => left ! msg
          case None => requester ! ContainsResult(id, false)
        }
      } else {
        subtrees.get(Right) match {
          case Some(right) => right ! msg
          case None => requester ! ContainsResult(id, false)
        }
      }
    case CopyTo(node) =>
      val children = subtrees.values.toSet
      if (this.removed && children.isEmpty)
        context.parent ! CopyFinished
      else {
        children map { _ ! CopyTo(node) }
        context.become(copying(children, removed))
        if (removed) self ! OperationFinished(elem)
        else node ! Insert(self, elem, elem)
      }
    case CopyFinished =>
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case CopyFinished =>
      val newExpected = expected - sender
      if (newExpected.isEmpty && insertConfirmed) {
        context.parent ! CopyFinished
        context.become(normal)
      } else context.become(copying(newExpected, insertConfirmed))
    case OperationFinished(id) =>
      if (expected.isEmpty) {
        context.parent ! CopyFinished
        context.become(normal)
      } else context.become(copying(expected, true))
  }

}
