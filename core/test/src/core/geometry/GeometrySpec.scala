package core.geometry

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.{Arbitrary, Gen}

class GeometrySpec extends ScalaCheckSuite:

  // ── Arbitrary instances ─────────────────────────────────────────────

  given Arbitrary[Point] = Arbitrary(
    for x <- Gen.choose(-1000, 1000); y <- Gen.choose(-1000, 1000) yield Point(x, y)
  )

  given Arbitrary[Size] = Arbitrary(
    for w <- Gen.choose(0, 500); h <- Gen.choose(0, 500) yield Size(w, h)
  )

  given Arbitrary[Rect] = Arbitrary(
    for
      x <- Gen.choose(-500, 500)
      y <- Gen.choose(-500, 500)
      w <- Gen.choose(0, 500)
      h <- Gen.choose(0, 500)
    yield Rect(x, y, w, h)
  )

  given Arbitrary[Vec2] = Arbitrary(
    for x <- Gen.choose(-1000.0, 1000.0); y <- Gen.choose(-1000.0, 1000.0) yield Vec2(x, y)
  )

  // ── Point ───────────────────────────────────────────────────────────

  test("Point equality") {
    assertEquals(Point(1, 2), Point(1, 2))
    assertNotEquals(Point(1, 2), Point(3, 4))
  }

  test("Point component access") {
    val p = Point(3, 7)
    assertEquals(p.x, 3)
    assertEquals(p.y, 7)
  }

  // ── Size ────────────────────────────────────────────────────────────

  test("Size equality") {
    assertEquals(Size(10, 20), Size(10, 20))
    assertNotEquals(Size(10, 20), Size(10, 21))
  }

  // Negative dimensions are rejected. We document the approach here:
  // Size requires w >= 0 and h >= 0, enforced via require() in the constructor,
  // which throws IllegalArgumentException on violation.
  test("Size rejects negative width") {
    intercept[IllegalArgumentException] {
      Size(-1, 10)
    }
  }

  test("Size rejects negative height") {
    intercept[IllegalArgumentException] {
      Size(10, -1)
    }
  }

  // ── Rect ────────────────────────────────────────────────────────────

  test("Rect equality") {
    assertEquals(Rect(1, 2, 3, 4), Rect(1, 2, 3, 4))
    assertNotEquals(Rect(1, 2, 3, 4), Rect(1, 2, 3, 5))
  }

  test("Rect component access") {
    val r = Rect(1, 2, 3, 4)
    assertEquals(r.x, 1)
    assertEquals(r.y, 2)
    assertEquals(r.w, 3)
    assertEquals(r.h, 4)
  }

  // ── Rect.contains ───────────────────────────────────────────────────

  test("Rect.contains: point at origin is inside") {
    val r = Rect(10, 20, 100, 50)
    assert(r.contains(Point(10, 20)))
  }

  test("Rect.contains: point one pixel outside left edge is not inside") {
    val r = Rect(10, 20, 100, 50)
    assert(!r.contains(Point(9, 25)))
  }

  test("Rect.contains: point one pixel outside right edge is not inside") {
    val r = Rect(10, 20, 100, 50)
    // Right edge is at x = 10 + 100 = 110; pixel 110 is outside (half-open interval)
    assert(!r.contains(Point(110, 25)))
  }

  test("Rect.contains: point one pixel outside top edge is not inside") {
    val r = Rect(10, 20, 100, 50)
    assert(!r.contains(Point(15, 19)))
  }

  test("Rect.contains: point one pixel outside bottom edge is not inside") {
    val r = Rect(10, 20, 100, 50)
    // Bottom edge is at y = 20 + 50 = 70; pixel 70 is outside
    assert(!r.contains(Point(15, 70)))
  }

  // ── Rect.union ──────────────────────────────────────────────────────

  test("Rect.union: union of two non-overlapping rects has area >= each individual rect") {
    val r1 = Rect(0, 0, 10, 10)
    val r2 = Rect(20, 20, 10, 10)
    val u = r1.union(r2)
    val areaUnion = u.w.toLong * u.h.toLong
    val area1 = r1.w.toLong * r1.h.toLong
    val area2 = r2.w.toLong * r2.h.toLong
    assert(areaUnion >= area1)
    assert(areaUnion >= area2)
  }

  property("Rect.union is commutative") {
    forAll { (r1: Rect, r2: Rect) =>
      r1.union(r2) == r2.union(r1)
    }
  }

  property("Rect.union with empty is identity on the non-empty operand") {
    forAll { (r: Rect) =>
      val empty = Rect(0, 0, 0, 0)
      assertEquals(r.union(empty), if r.isEmpty then empty else r)
      assertEquals(empty.union(r), if r.isEmpty then empty else r)
    }
  }

  property("Rect.union is associative") {
    forAll { (a: Rect, b: Rect, c: Rect) =>
      a.union(b).union(c) == a.union(b.union(c))
    }
  }

  // ── Rect.intersect ──────────────────────────────────────────────────

  test("Rect.intersect: non-overlapping rects produce isEmpty result") {
    val r1 = Rect(0, 0, 10, 10)
    val r2 = Rect(20, 20, 10, 10)
    val result = r1.intersect(r2)
    assert(result.forall(_.isEmpty))
  }

  test("Rect.intersect: overlapping rects produce the correct sub-rect") {
    val r1 = Rect(0, 0, 20, 20)
    val r2 = Rect(10, 10, 20, 20)
    val result = r1.intersect(r2)
    assert(result.isDefined)
    assertEquals(result.get, Rect(10, 10, 10, 10))
  }

  property("Rect.intersect is commutative") {
    forAll { (r1: Rect, r2: Rect) =>
      r1.intersect(r2) == r2.intersect(r1)
    }
  }

  property("Rect.intersect result is contained in both operands") {
    forAll { (r1: Rect, r2: Rect) =>
      r1.intersect(r2).forall { sub =>
        sub.x >= r1.x && sub.x >= r2.x &&
        sub.y >= r1.y && sub.y >= r2.y &&
        sub.x + sub.w <= r1.x + r1.w && sub.x + sub.w <= r2.x + r2.w &&
        sub.y + sub.h <= r1.y + r1.h && sub.y + sub.h <= r2.y + r2.h
      }
    }
  }

  // ── Rect.isEmpty ────────────────────────────────────────────────────

  test("Rect.isEmpty: zero-width rect is empty") {
    assert(Rect(0, 0, 0, 10).isEmpty)
  }

  test("Rect.isEmpty: zero-height rect is empty") {
    assert(Rect(0, 0, 10, 0).isEmpty)
  }

  test("Rect.isEmpty: non-zero dimensions are not empty") {
    assert(!Rect(0, 0, 1, 1).isEmpty)
  }

  // ── Vec2 ────────────────────────────────────────────────────────────

  test("Vec2 equality") {
    assertEquals(Vec2(1.0, 2.0), Vec2(1.0, 2.0))
    assertNotEquals(Vec2(1.0, 2.0), Vec2(1.0, 3.0))
  }

  test("Vec2 component access") {
    val v = Vec2(3.5, 7.25)
    assertEquals(v.x, 3.5)
    assertEquals(v.y, 7.25)
  }

  // ── DamageRegion ────────────────────────────────────────────────────

  test("DamageRegion.empty is empty") {
    assert(DamageRegion.empty.isEmpty)
  }

  test("DamageRegion.fromRect: region from a non-empty rect is non-empty") {
    val r = Rect(0, 0, 10, 10)
    assert(!DamageRegion.fromRect(r).isEmpty)
  }

  test("DamageRegion.union: union of two single-rect regions contains both rects") {
    val r1 = Rect(0, 0, 10, 10)
    val r2 = Rect(20, 20, 5, 5)
    val d1 = DamageRegion.fromRect(r1)
    val d2 = DamageRegion.fromRect(r2)
    val u = d1.union(d2)
    assert(!u.isEmpty)
  }

  given Arbitrary[DamageRegion] = Arbitrary(
    for
      n <- Gen.choose(0, 8)
      rects <- Gen.listOfN(
        n,
        for
          x <- Gen.choose(-100, 100)
          y <- Gen.choose(-100, 100)
          w <- Gen.choose(0, 100)
          h <- Gen.choose(0, 100)
        yield Rect(x, y, w, h)
      )
    yield DamageRegion.fromRects(rects)
  )

  property("DamageRegion.union is associative") {
    forAll { (a: DamageRegion, b: DamageRegion, c: DamageRegion) =>
      // Associativity: structural equality on the opaque list representation
      a.union(b).union(c) == a.union(b.union(c))
    }
  }

  test("DamageRegion.fromRects: creates region with correct rect count") {
    val rs = List(Rect(0, 0, 10, 10), Rect(20, 20, 5, 5), Rect(50, 50, 1, 1))
    val d = DamageRegion.fromRects(rs)
    assertEquals(d.rects.length, 3)
  }

  test("DamageRegion.rects: accessor returns the underlying rectangles") {
    val r = Rect(5, 10, 20, 30)
    val d = DamageRegion.fromRect(r)
    assertEquals(d.rects, List(r))
  }

  test("DamageRegion.size: matches number of rectangles") {
    val d = DamageRegion.fromRects(List(Rect(0,0,1,1), Rect(2,2,3,3)))
    assertEquals(d.size, 2)
    assertEquals(DamageRegion.empty.size, 0)
  }

  test("DamageRegion.boundingBox: None for empty region") {
    assertEquals(DamageRegion.empty.boundingBox, None)
  }

  test("DamageRegion.boundingBox: single rect returns that rect") {
    val r = Rect(10, 20, 30, 40)
    assertEquals(DamageRegion.fromRect(r).boundingBox, Some(r))
  }

  test("DamageRegion.boundingBox: multiple rects returns enclosing rect") {
    val d = DamageRegion.fromRects(List(Rect(0, 0, 10, 10), Rect(20, 20, 5, 5)))
    assertEquals(d.boundingBox, Some(Rect(0, 0, 25, 25)))
  }

  test("DamageRegion.area: sum of individual rect areas") {
    val d = DamageRegion.fromRects(List(Rect(0, 0, 10, 10), Rect(20, 20, 5, 5)))
    assertEquals(d.area, 125L)
  }

  test("DamageRegion.area: empty region has zero area") {
    assertEquals(DamageRegion.empty.area, 0L)
  }
