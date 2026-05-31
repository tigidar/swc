package core.state

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import kyo.*
import core.input.*
import core.output.OutputId
import core.windows.WindowId
import core.geometry.{Rect, Vec2}

/**
 * Specification for the pure `InputHandler` — keyboard section.
 *
 * Tests the algebra of `InputEvent` → state changes + emitted `ShellEffect`s
 * for keyboard device lifecycle and key press / release dispatch. These tests
 * exercise `InputHandler.handle` against `EventHandler.run` the same way the
 * compositor shell will at runtime, so they catch any divergence between the
 * pure model and the shell's expected side effects.
 *
 * The tests are written ahead of the implementation (pure-06); until that
 * ticket lands they will fail at compile time because `InputHandler` does
 * not yet exist.
 */
class InputHandlerSpec extends ScalaCheckSuite:

  private val cfg = CompositorConfig.default
  private val kid  = KeyboardId("kbd-1")
  private val kid2 = KeyboardId("kbd-2")
  private val out1 = OutputId("HDMI-A-1")

  /** Build a state that already has one output (auto-focused, workspace 1). */
  private def stateWithOutput: CompositorState =
    val (s, _, _) = EventHandler.run(cfg, CompositorState.empty)(
      EventHandler.addOutput(out1, 1920, 1080, 0, 0)
    )
    s

  // XKB keysym constants — mirroring the values used by KeyBindingMap.default.
  private val XKB_Escape: KeySym = KeySym(0xff1b)
  private val XKB_a:      KeySym = KeySym(0x0061)

  private def run[A](state: CompositorState)(pipeline: A < EventHandler.Effects)(using Frame) =
    EventHandler.run(cfg, state)(pipeline)

  // ── KeyboardAdded ──────────────────────────────────────────────────

  test("KeyboardAdded registers keyboard in state") {
    val (s, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyboardAdded(kid))
    )
    assert(s.keyboards.contains(kid), s"expected $kid in ${s.keyboards}")
  }

  test("KeyboardAdded first keyboard becomes activeKeyboard") {
    val (s, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyboardAdded(kid))
    )
    assertEquals(s.activeKeyboard, Some(kid))
  }

  test("KeyboardAdded second keyboard does not change activeKeyboard") {
    val (s1, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyboardAdded(kid))
    )
    val (s2, _, _) = run(s1)(
      InputHandler.handle(InputEvent.KeyboardAdded(kid2))
    )
    assertEquals(s2.activeKeyboard, Some(kid))
    assert(s2.keyboards.contains(kid),  s"expected $kid in ${s2.keyboards}")
    assert(s2.keyboards.contains(kid2), s"expected $kid2 in ${s2.keyboards}")
  }

  test("KeyboardAdded emits SetActiveKeyboard for first keyboard") {
    val (_, effects, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyboardAdded(kid))
    )
    assert(
      effects.toSeq.contains(ShellEffect.SetActiveKeyboard(kid)),
      s"expected SetActiveKeyboard($kid) in ${effects.toSeq}"
    )
  }

  // ── KeyboardRemoved ────────────────────────────────────────────────

  test("KeyboardRemoved removes keyboard from state") {
    val (s1, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyboardAdded(kid))
    )
    val (s2, _, _) = run(s1)(
      InputHandler.handle(InputEvent.KeyboardRemoved(kid))
    )
    assert(s2.keyboards.isEmpty, s"expected empty, got ${s2.keyboards}")
  }

  test("KeyboardRemoved clears activeKeyboard when active keyboard removed") {
    val (s1, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyboardAdded(kid))
    )
    val (s2, _, _) = run(s1)(
      InputHandler.handle(InputEvent.KeyboardRemoved(kid))
    )
    assertEquals(s2.activeKeyboard, None)
  }

  test("KeyboardRemoved activeKeyboard falls back to next keyboard when active removed") {
    val (s1, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyboardAdded(kid))
    )
    val (s2, _, _) = run(s1)(
      InputHandler.handle(InputEvent.KeyboardAdded(kid2))
    )
    val (s3, _, _) = run(s2)(
      InputHandler.handle(InputEvent.KeyboardRemoved(kid))
    )
    assertEquals(s3.activeKeyboard, Some(kid2))
  }

  // ── KeyRelease ─────────────────────────────────────────────────────

  test("KeyRelease emits ForwardKeyToClient with pressed=false") {
    val (_, effects, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyRelease(kid, keycode = 42, time = 100L))
    )
    assert(
      effects.toSeq.contains(ShellEffect.ForwardKeyToClient(kid, 100L, 42, false)),
      s"expected ForwardKeyToClient($kid, 100, 42, false) in ${effects.toSeq}"
    )
  }

  // ── KeyPress ───────────────────────────────────────────────────────

  test("KeyPress with bound keysym emits matching ShellEffect and does not emit ForwardKeyToClient") {
    val (_, effects, _) = run(CompositorState.empty)(
      InputHandler.handle(
        InputEvent.KeyPress(
          keyboardId = kid,
          keysym     = XKB_Escape,
          rawKeysym  = XKB_Escape,
          modifiers  = Modifiers.Super,
          keycode    = 9,
          time       = 0L
        )
      )
    )
    val seq = effects.toSeq
    assert(seq.contains(ShellEffect.TerminateDisplay), s"expected TerminateDisplay in $seq")
    assert(
      !seq.exists(_.isInstanceOf[ShellEffect.ForwardKeyToClient]),
      s"expected no ForwardKeyToClient in $seq"
    )
  }

  test("KeyPress with unbound keysym emits ForwardKeyToClient") {
    val (_, effects, _) = run(CompositorState.empty)(
      InputHandler.handle(
        InputEvent.KeyPress(
          keyboardId = kid,
          keysym     = XKB_a,
          rawKeysym  = XKB_a,
          modifiers  = Modifiers.None,
          keycode    = 38,
          time       = 123L
        )
      )
    )
    assert(
      effects.toSeq.contains(ShellEffect.ForwardKeyToClient(kid, 123L, 38, true)),
      s"expected ForwardKeyToClient($kid, 123, 38, true) in ${effects.toSeq}"
    )
  }

  test("KeyPress sets active keyboard via SetActiveKeyboard effect") {
    val (s1, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyboardAdded(kid))
    )
    val (_, effects, _) = run(s1)(
      InputHandler.handle(
        InputEvent.KeyPress(
          keyboardId = kid,
          keysym     = XKB_a,
          rawKeysym  = XKB_a,
          modifiers  = Modifiers.None,
          keycode    = 38,
          time       = 0L
        )
      )
    )
    assert(
      effects.toSeq.contains(ShellEffect.SetActiveKeyboard(kid)),
      s"expected SetActiveKeyboard($kid) in ${effects.toSeq}"
    )
  }

  // ── Window lifecycle ───────────────────────────────────────────────

  test("WindowMapped adds window to output active workspace") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(
      InputHandler.handle(InputEvent.WindowMapped(id, out1, Some("w"), None))
    )
    val ws = s2.outputs(out1).active
    assert(ws.windows.get(id).isDefined, s"expected window $id on active workspace, got ${ws.windows.ordered}")
    assertEquals(ws.focus.focused, Some(id))
  }

  test("WindowMapped emits RetileOutput and SetKeyboardFocus") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (_, effects, _) = run(s1)(
      InputHandler.handle(InputEvent.WindowMapped(id, out1, Some("w"), None))
    )
    val seq = effects.toSeq
    assert(seq.contains(ShellEffect.RetileOutput(out1)), s"expected RetileOutput($out1) in $seq")
    assert(seq.contains(ShellEffect.SetKeyboardFocus(id)), s"expected SetKeyboardFocus($id) in $seq")
  }

  test("WindowUnmapped clears mapped flag and transfers focus") {
    val s0 = stateWithOutput
    val (s1, _, idA) = run(s0)(EventHandler.createWindow(Some("a"), None))
    val (s2, _, idB) = run(s1)(EventHandler.createWindow(Some("b"), None))
    val (s3, _, _) = run(s2)(EventHandler.mapWindow(idA, out1, Some("a"), None))
    val (s4, _, _) = run(s3)(EventHandler.mapWindow(idB, out1, Some("b"), None))
    // B has focus (most recently mapped). Refocus A so unmapping A transfers focus to B.
    val (s5, _, _) = run(s4)(EventHandler.requestFocus(idA))
    val (s6, _, _) = run(s5)(
      InputHandler.handle(InputEvent.WindowUnmapped(idA))
    )
    val ws = s6.outputs(out1).active
    assert(
      ws.windows.get(idA).exists(!_.mapped),
      s"expected windowA mapped=false, got ${ws.windows.get(idA)}"
    )
    assertEquals(ws.focus.focused, Some(idB))
  }

  test("WindowUnmapped emits RetileOutput and SetKeyboardFocus on remaining window") {
    val s0 = stateWithOutput
    val (s1, _, idA) = run(s0)(EventHandler.createWindow(Some("a"), None))
    val (s2, _, idB) = run(s1)(EventHandler.createWindow(Some("b"), None))
    val (s3, _, _) = run(s2)(EventHandler.mapWindow(idA, out1, Some("a"), None))
    val (s4, _, _) = run(s3)(EventHandler.mapWindow(idB, out1, Some("b"), None))
    val (s5, _, _) = run(s4)(EventHandler.requestFocus(idA))
    val (_, effects, _) = run(s5)(
      InputHandler.handle(InputEvent.WindowUnmapped(idA))
    )
    val seq = effects.toSeq
    assert(seq.contains(ShellEffect.RetileOutput(out1)), s"expected RetileOutput($out1) in $seq")
    assert(seq.contains(ShellEffect.SetKeyboardFocus(idB)), s"expected SetKeyboardFocus($idB) in $seq")
  }

  test("WindowDestroyed removes window from state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, _, _) = run(s2)(
      InputHandler.handle(InputEvent.WindowDestroyed(id))
    )
    assertEquals(s3.outputs(out1).active.windows.get(id), None)
  }

  test("WindowDestroyed on window under grab clears grab state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val s3 = s2.copy(grabState = Some(GrabState.Moving(id, 0.0, 0.0, 0, 0)))
    val (s4, _, _) = run(s3)(
      InputHandler.handle(InputEvent.WindowDestroyed(id))
    )
    assertEquals(s4.grabState, None)
  }

  test("RequestMove sets Moving grab state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, _, _) = run(s2)(
      InputHandler.handle(
        InputEvent.RequestMove(id, cursorX = 100.0, cursorY = 200.0, windowX = 50, windowY = 60)
      )
    )
    assertEquals(s3.grabState, Some(GrabState.Moving(id, 100.0, 200.0, 50, 60)))
  }

  test("RequestResize sets Resizing grab state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, _, _) = run(s2)(
      InputHandler.handle(
        InputEvent.RequestResize(
          id,
          edges   = 8,
          cursorX = 100.0,
          cursorY = 200.0,
          windowX = 50,
          windowY = 60,
          windowW = 400,
          windowH = 300
        )
      )
    )
    assertEquals(
      s3.grabState,
      Some(GrabState.Resizing(id, 100.0, 200.0, 50, 60, 400, 300, edges = 8))
    )
  }

  test("RequestFullscreen toggles fullscreen on focused window") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, _, _) = run(s2)(
      InputHandler.handle(InputEvent.RequestFullscreen(id))
    )
    assert(
      s3.outputs(out1).active.windows.get(id).exists(_.fullscreen),
      s"expected window $id fullscreen=true, got ${s3.outputs(out1).active.windows.get(id)}"
    )
  }

  test("handleNewWindow allocates a WindowId and increments nextId") {
    val (s, _, id) = run(CompositorState.empty)(
      InputHandler.handleNewWindow(Some("t"), None)
    )
    assertEquals(WindowId.value(id), 0L)
    assertEquals(s.nextId, 1L)
  }

  // ── Pointer events ─────────────────────────────────────────────────
  //
  // These tests exercise `PointerMoved` / `PointerButton` dispatch. They
  // drive both (a) emitted `ShellEffect`s — `PointerEnter`, `PointerMotion`,
  // `ClearPointerFocus`, `SetDefaultCursor`, `MoveWindow`, `ResizeWindow`,
  // `SetKeyboardFocus` — and (b) state mutations: `cursorX/Y`,
  // `pointerFocus`, `grabState`. Tests will fail until pure-10 replaces the
  // pointer stubs in `InputHandler.handle` with real implementations.

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
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _)  = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val (_, effects, _) = run(s2)(
      InputHandler.handle(InputEvent.PointerMoved(10.0, 20.0, 100L, Focus(id, Vec2(1.0, 2.0))))
    )
    val seq = effects.toSeq
    assert(seq.contains(ShellEffect.PointerEnter(id, 1.0, 2.0)), s"expected PointerEnter($id, 1.0, 2.0) in $seq")
    assert(!seq.contains(ShellEffect.ClearPointerFocus),         s"did not expect ClearPointerFocus in $seq")
  }

  test("PointerMoved with same Focus as before emits PointerMotion not PointerEnter") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _)  = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
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
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _)  = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
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

  // ── Output lifecycle ───────────────────────────────────────────────
  //
  // Smoke tests verifying `InputHandler.handle` delegates each output
  // event to the matching `EventHandler` method. Full output coverage
  // lives in `EventHandlerSpec`; these tests only confirm the wiring.
  // They fail until pure-12 fills in the output stubs in
  // `InputHandler.handle`.

  test("OutputAdded delegates to EventHandler: output appears in state") {
    val (s, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.OutputAdded(out1, 1920, 1080, 0, 0))
    )
    assert(s.outputs.contains(out1), s"expected $out1 in ${s.outputs.keySet}")
    assertEquals(s.outputOrder, List(out1))
  }

  test("OutputAdded first output becomes focusedOutput") {
    val (s, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.OutputAdded(out1, 1920, 1080, 0, 0))
    )
    assertEquals(s.focusedOutput, Some(out1))
  }

  test("OutputRemoved delegates to EventHandler: output removed from state") {
    val (s1, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.OutputAdded(out1, 1920, 1080, 0, 0))
    )
    val (s2, _, _) = run(s1)(
      InputHandler.handle(InputEvent.OutputRemoved(out1))
    )
    assert(s2.outputs.isEmpty, s"expected empty outputs, got ${s2.outputs.keySet}")
  }

  test("UsableAreaChanged delegates to EventHandler: usable area updated") {
    val newArea = Rect(0, 30, 1920, 1050)
    val (s1, _, _) = run(CompositorState.empty)(
      InputHandler.handle(InputEvent.OutputAdded(out1, 1920, 1080, 0, 0))
    )
    val (s2, _, _) = run(s1)(
      InputHandler.handle(InputEvent.UsableAreaChanged(out1, newArea))
    )
    assertEquals(s2.outputs(out1).usableArea, newArea)
  }
