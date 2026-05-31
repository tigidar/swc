package core.state

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.{Arbitrary, Gen}
import core.windows.WindowId
import core.geometry.Rect
import core.state.GrabState.ResizeEdge

class GrabStateSpec extends ScalaCheckSuite:

  // ── Generators ──────────────────────────────────────────────────────

  private val genPos = Gen.choose(-4000, 4000)
  private val genSize = Gen.choose(10, 2000)
  private val genCursor = Gen.choose(-5000.0, 5000.0)

  private val genMoving: Gen[GrabState.Moving] = for
    startCX <- genCursor
    startCY <- genCursor
    startX  <- genPos
    startY  <- genPos
  yield GrabState.Moving(WindowId(1L), startCX, startCY, startX, startY)

  private val genEdges = Gen.oneOf(
    ResizeEdge.Right,
    ResizeEdge.Left,
    ResizeEdge.Top,
    ResizeEdge.Bottom,
    ResizeEdge.Top | ResizeEdge.Left,
    ResizeEdge.Top | ResizeEdge.Right,
    ResizeEdge.Bottom | ResizeEdge.Left,
    ResizeEdge.Bottom | ResizeEdge.Right
  )

  private val genResizing: Gen[GrabState.Resizing] = for
    startCX <- genCursor
    startCY <- genCursor
    startX  <- genPos
    startY  <- genPos
    startW  <- genSize
    startH  <- genSize
    edges   <- genEdges
  yield GrabState.Resizing(WindowId(1L), startCX, startCY, startX, startY, startW, startH, edges)

  // ── computeMove ─────────────────────────────────────────────────────

  private def moving(scx: Double, scy: Double, sx: Int, sy: Int): GrabState.Moving =
    GrabState.Moving(WindowId(1L), scx, scy, sx, sy)

  test("computeMove: zero cursor delta produces start position") {
    val grab = moving(100.0, 200.0, 50, 60)
    val (x, y) = GrabState.computeMove(grab, 100.0, 200.0)
    assertEquals(x, 50)
    assertEquals(y, 60)
  }

  test("computeMove: positive delta moves right and down") {
    val grab = moving(100.0, 200.0, 50, 60)
    val (x, y) = GrabState.computeMove(grab, 110.0, 215.0)
    assertEquals(x, 60)  // 50 + 10
    assertEquals(y, 75)  // 60 + 15
  }

  test("computeMove: negative delta moves left and up") {
    val grab = moving(100.0, 200.0, 50, 60)
    val (x, y) = GrabState.computeMove(grab, 90.0, 180.0)
    assertEquals(x, 40)  // 50 - 10
    assertEquals(y, 40)  // 60 - 20
  }

  property("computeMove: delta is linear (cursor - start applied to position)") {
    forAll(genMoving, genCursor, genCursor) { (grab, cx, cy) =>
      val (x, y) = GrabState.computeMove(grab, cx, cy)
      val expectedX = grab.startX + (cx - grab.startCursorX).toInt
      val expectedY = grab.startY + (cy - grab.startCursorY).toInt
      x == expectedX && y == expectedY
    }
  }

  // ── computeResize ───────────────────────────────────────────────────

  private def resizing(scx: Double, scy: Double, sx: Int, sy: Int, sw: Int, sh: Int, edges: Int): GrabState.Resizing =
    GrabState.Resizing(WindowId(1L), scx, scy, sx, sy, sw, sh, edges)

  test("computeResize: zero cursor delta preserves geometry") {
    val grab = resizing(100.0, 200.0, 10, 20, 300, 400, ResizeEdge.Right)
    val rect = GrabState.computeResize(grab, 100.0, 200.0)
    assertEquals(rect, Rect(10, 20, 300, 400))
  }

  test("computeResize: right edge grows width") {
    val grab = resizing(100.0, 200.0, 10, 20, 300, 400, ResizeEdge.Right)
    val rect = GrabState.computeResize(grab, 150.0, 200.0)
    assertEquals(rect.x, 10)   // unchanged
    assertEquals(rect.w, 350)  // 300 + 50
    assertEquals(rect.h, 400)  // unchanged
  }

  test("computeResize: left edge moves x and shrinks width") {
    val grab = resizing(100.0, 200.0, 10, 20, 300, 400, ResizeEdge.Left)
    val rect = GrabState.computeResize(grab, 130.0, 200.0)
    assertEquals(rect.x, 40)   // 10 + 30
    assertEquals(rect.w, 270)  // 300 - 30
  }

  test("computeResize: bottom edge grows height") {
    val grab = resizing(100.0, 200.0, 10, 20, 300, 400, ResizeEdge.Bottom)
    val rect = GrabState.computeResize(grab, 100.0, 260.0)
    assertEquals(rect.y, 20)   // unchanged
    assertEquals(rect.h, 460)  // 400 + 60
  }

  test("computeResize: top edge moves y and shrinks height") {
    val grab = resizing(100.0, 200.0, 10, 20, 300, 400, ResizeEdge.Top)
    val rect = GrabState.computeResize(grab, 100.0, 240.0)
    assertEquals(rect.y, 60)   // 20 + 40
    assertEquals(rect.h, 360)  // 400 - 40
  }

  test("computeResize: bottom-right corner resizes both") {
    val edges = ResizeEdge.Bottom | ResizeEdge.Right
    val grab = resizing(100.0, 200.0, 10, 20, 300, 400, edges)
    val rect = GrabState.computeResize(grab, 120.0, 230.0)
    assertEquals(rect.x, 10)   // unchanged
    assertEquals(rect.y, 20)   // unchanged
    assertEquals(rect.w, 320)  // 300 + 20
    assertEquals(rect.h, 430)  // 400 + 30
  }

  property("computeResize: width and height are always >= 1") {
    forAll(genResizing, genCursor, genCursor) { (grab, cx, cy) =>
      val rect = GrabState.computeResize(grab, cx, cy)
      rect.w >= 1 && rect.h >= 1
    }
  }

  property("computeResize: edges not in mask are unaffected") {
    forAll(genResizing, genCursor, genCursor) { (grab, cx, cy) =>
      val rect = GrabState.computeResize(grab, cx, cy)
      val horizUnchanged = if (grab.edges & ResizeEdge.Right) == 0 && (grab.edges & ResizeEdge.Left) == 0 then
        rect.x == grab.startX && rect.w == grab.startW
      else true
      val vertUnchanged = if (grab.edges & ResizeEdge.Top) == 0 && (grab.edges & ResizeEdge.Bottom) == 0 then
        rect.y == grab.startY && rect.h == grab.startH
      else true
      horizUnchanged && vertUnchanged
    }
  }

  // ── windowId ────────────────────────────────────────────────────────

  test("windowId extracts id from Moving") {
    val grab: GrabState = GrabState.Moving(WindowId(42L), 0, 0, 0, 0)
    assertEquals(WindowId.value(grab.windowId), 42L)
  }

  test("windowId extracts id from Resizing") {
    val grab: GrabState = GrabState.Resizing(WindowId(7L), 0, 0, 0, 0, 100, 100, ResizeEdge.Right)
    assertEquals(WindowId.value(grab.windowId), 7L)
  }
