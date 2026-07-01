package core.state

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import kyo.*
import core.input.*
import core.output.OutputId
import core.windows.WindowId
import core.geometry.Vec2

/**
 * Specification for the pure `PointerHandler`.
 *
 * Drives `PointerMoved` / `PointerButton` through `InputHandler.handle` — the
 * realistic runtime path the compositor shell uses — and verifies both
 * (a) emitted `ShellEffect`s (`PointerEnter`, `PointerMotion`,
 * `ClearPointerFocus`, `SetDefaultCursor`, `MoveWindow`, `ResizeWindow`,
 * `SetKeyboardFocus`) and (b) state mutations (`cursorX/Y`, `pointerFocus`,
 * `grabState`).
 */
class PointerHandlerSpec extends ScalaCheckSuite:

  private val cfg = CompositorConfig.default
  private val out1 = OutputId("HDMI-A-1")

  /** Build a state that already has one output (auto-focused, workspace 1). */
  private def stateWithOutput: CompositorState =
    val (s, _, _) = EventHandler.run(cfg, CompositorState.empty)(
      OutputHandler.addOutput(out1, 1920, 1080, 0, 0)
    )
    s

  private def run[A](state: CompositorState)(pipeline: A < EventHandler.Effects)(using Frame) =
    EventHandler.run(cfg, state)(pipeline)

  // Generators for property-based pointer tests (parameters in the same
  // ranges used by GrabStateSpec so the two specs exercise the same space).
  private val genPos    = Gen.choose(-4000, 4000)
  private val genSize   = Gen.choose(10, 2000)
  private val genCursor = Gen.choose(-5000.0, 5000.0)

  private val genMoving: Gen[GrabState.Moving] = for
    scx <- genCursor
    scy <- genCursor
    sx  <- genPos
    sy  <- genPos
  yield GrabState.Moving(WindowId(1L), scx, scy, sx, sy)

  private val genEdges = Gen.oneOf(
    GrabState.ResizeEdge.Right,
    GrabState.ResizeEdge.Left,
    GrabState.ResizeEdge.Top,
    GrabState.ResizeEdge.Bottom,
    GrabState.ResizeEdge.Top    | GrabState.ResizeEdge.Left,
    GrabState.ResizeEdge.Top    | GrabState.ResizeEdge.Right,
    GrabState.ResizeEdge.Bottom | GrabState.ResizeEdge.Left,
    GrabState.ResizeEdge.Bottom | GrabState.ResizeEdge.Right
  )

  private val genResizing: Gen[GrabState.Resizing] = for
    scx   <- genCursor
    scy   <- genCursor
    sx    <- genPos
    sy    <- genPos
    sw    <- genSize
    sh    <- genSize
    edges <- genEdges
  yield GrabState.Resizing(WindowId(1L), scx, scy, sx, sy, sw, sh, edges)

  test("PointerMoved with NoFocus and no grab emits ClearPointerFocus and SetDefaultCursor") {
    val (_, effects, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.PointerMoved(10.0, 20.0, 100L, NoFocus))
    )
    val seq = effects.toSeq
    assert(seq.contains(ShellEffect.ClearPointerFocus), s"expected ClearPointerFocus in $seq")
    assert(seq.contains(ShellEffect.SetDefaultCursor),  s"expected SetDefaultCursor in $seq")
  }

  test("PointerMoved with NoFocus updates cursorX and cursorY in state") {
    val (s, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.PointerMoved(10.0, 20.0, 100L, NoFocus))
    )
    assertEquals(s.cursorX, 10.0)
    assertEquals(s.cursorY, 20.0)
  }

  test("PointerMoved with Focus on known window emits PointerEnter") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _)  = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val (_, effects, _) = run(s2)(
      InputHandler.handle(InputEvent.PointerMoved(10.0, 20.0, 100L, Focus(id, Vec2(1.0, 2.0))))
    )
    val seq = effects.toSeq
    assert(seq.contains(ShellEffect.PointerEnter(id, 1.0, 2.0)), s"expected PointerEnter($id, 1.0, 2.0) in $seq")
    assert(!seq.contains(ShellEffect.ClearPointerFocus),         s"did not expect ClearPointerFocus in $seq")
  }

  test("PointerMoved with same Focus as before emits PointerMotion not PointerEnter") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _)  = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val s3          = s2.copy(pointerFocus = Some(id))
    val (_, effects, _) = run(s3)(
      InputHandler.handle(InputEvent.PointerMoved(10.0, 20.0, 100L, Focus(id, Vec2(1.0, 2.0))))
    )
    val seq = effects.toSeq
    assert(seq.contains(ShellEffect.PointerMotion(100L, 1.0, 2.0)), s"expected PointerMotion(100, 1.0, 2.0) in $seq")
    assert(!seq.exists(_.isInstanceOf[ShellEffect.PointerEnter]),   s"did not expect PointerEnter in $seq")
  }

  test("PointerMoved with Moving grab emits MoveWindow with correct geometry") {
    val id   = WindowId(1L)
    val grab = GrabState.Moving(id, startCursorX = 0.0, startCursorY = 0.0, startX = 100, startY = 200)
    val s0   = CompositorState.empty.copy(grabState = Some(grab))
    val (_, effects, _) = run(s0)(
      InputHandler.handle(InputEvent.PointerMoved(50.0, 30.0, 0L, NoFocus))
    )
    assert(
      effects.toSeq.contains(ShellEffect.MoveWindow(id, 150, 230)),
      s"expected MoveWindow($id, 150, 230) in ${effects.toSeq}"
    )
  }

  property("PointerMoved with Moving grab: MoveWindow geometry matches GrabState.computeMove") {
    forAll(genMoving, genCursor, genCursor) { (grab, cx, cy) =>
      val s0 = CompositorState.empty.copy(grabState = Some(grab))
      val (_, effects, _) = run(s0)(
        InputHandler.handle(InputEvent.PointerMoved(cx, cy, 0L, NoFocus))
      )
      val (x, y) = GrabState.computeMove(grab, cx, cy)
      effects.toSeq.contains(ShellEffect.MoveWindow(grab.id, x, y))
    }
  }

  test("PointerMoved with Resizing grab emits ResizeWindow with correct geometry") {
    val id   = WindowId(1L)
    val grab = GrabState.Resizing(id, 0.0, 0.0, 100, 200, 400, 300, GrabState.ResizeEdge.Right)
    val s0   = CompositorState.empty.copy(grabState = Some(grab))
    val (_, effects, _) = run(s0)(
      InputHandler.handle(InputEvent.PointerMoved(50.0, 0.0, 0L, NoFocus))
    )
    assert(
      effects.toSeq.contains(ShellEffect.ResizeWindow(id, 100, 200, 450, 300)),
      s"expected ResizeWindow($id, 100, 200, 450, 300) in ${effects.toSeq}"
    )
  }

  property("PointerMoved with Resizing grab: ResizeWindow geometry matches GrabState.computeResize") {
    forAll(genResizing, genCursor, genCursor) { (grab, cx, cy) =>
      val s0 = CompositorState.empty.copy(grabState = Some(grab))
      val (_, effects, _) = run(s0)(
        InputHandler.handle(InputEvent.PointerMoved(cx, cy, 0L, NoFocus))
      )
      val r = GrabState.computeResize(grab, cx, cy)
      effects.toSeq.contains(ShellEffect.ResizeWindow(grab.id, r.x, r.y, r.w, r.h))
    }
  }

  test("PointerButton press with Focus calls requestFocus on the window") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _)  = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, effects, _) = run(s2)(
      InputHandler.handle(
        InputEvent.PointerButton(
          button  = 1,
          pressed = true,
          time    = 0L,
          cursorX = 0.0,
          cursorY = 0.0,
          focus   = Focus(id, Vec2(0.0, 0.0))
        )
      )
    )
    assertEquals(s3.outputs(out1).active.focus.focused, Some(id))
    assert(
      effects.toSeq.contains(ShellEffect.SetKeyboardFocus(id)),
      s"expected SetKeyboardFocus($id) in ${effects.toSeq}"
    )
  }

  test("PointerButton release with active grab clears grab state") {
    val id = WindowId(1L)
    val s0 = CompositorState.empty.copy(grabState = Some(GrabState.Moving(id, 0.0, 0.0, 0, 0)))
    val (s1, _, _) = run(s0)(
      InputHandler.handle(
        InputEvent.PointerButton(
          button  = 1,
          pressed = false,
          time    = 0L,
          cursorX = 0.0,
          cursorY = 0.0,
          focus   = NoFocus
        )
      )
    )
    assertEquals(s1.grabState, None)
  }

  test("PointerButton press with NoFocus does not call requestFocus") {
    val (_, effects, _) = run(CompositorState.empty)(
      InputHandler.handle(
        InputEvent.PointerButton(
          button  = 1,
          pressed = true,
          time    = 0L,
          cursorX = 0.0,
          cursorY = 0.0,
          focus   = NoFocus
        )
      )
    )
    assert(
      !effects.toSeq.exists(_.isInstanceOf[ShellEffect.SetKeyboardFocus]),
      s"did not expect SetKeyboardFocus in ${effects.toSeq}"
    )
  }
