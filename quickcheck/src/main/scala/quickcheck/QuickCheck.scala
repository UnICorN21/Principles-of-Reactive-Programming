package quickcheck

import common._

import org.scalacheck._
import Arbitrary._
import Gen._
import Prop._

abstract class QuickCheckHeap extends Properties("Heap") with IntHeap {

  property("min1") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
  }

  property("min2") = forAll { (a: Int, b: Int) =>
    val h = insert(a, insert(b, empty))
    findMin(h) == (if (a > b) b else a)
  }

  property("meldMin") = forAll { (h1: H, h2: H) =>
    val h = meld(h1, h2)
    findMin(h) == (if (findMin(h1) > findMin(h2)) findMin(h2) else findMin(h1))
  }

  property("empty") = forAll { a: Int =>
  	deleteMin(insert(a, empty)) == empty
  }

  property("sort") = forAll { h: H =>
    def inner(h: H): List[Int] = if (isEmpty(h)) Nil else findMin(h)::inner(deleteMin(h))
    val list = inner(h)
    list == list.sorted
  }

  property("sort2") = forAll { l: List[Int] =>
    def inner1(l: List[Int]): H = l match {
      case Nil => empty
      case head :: tail => insert(head, inner1(tail))
    }
    def inner2(h: H): List[Int] = if (isEmpty(h)) Nil else findMin(h)::inner2(deleteMin(h))
    inner2(inner1(l)) == l.sorted
  }

  val genEmpty = const(empty)

  lazy val genHeap: Gen[H] = for {
    x <- arbitrary[Int]
    h <- oneOf(genEmpty, genHeap)
  } yield insert(x, h)

  implicit lazy val arbHeap: Arbitrary[H] = Arbitrary(genHeap)

}
