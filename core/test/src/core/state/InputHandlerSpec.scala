package core.state

import munit.ScalaCheckSuite
import kyo.*
import core.input.*
import core.output.OutputId
import core.windows.WindowId
import core.geometry.Rect

/**
 * Specification for the pure `InputHandler` dispatcher.
 *
 * `InputHandler.handle` records activity then routes each `InputEvent` to the
 * sibling handler that owns it. Per-domain algebra is covered by the sibling
 * specs ([[KeyboardHandlerSpec]], [[PointerHandlerSpec]],
 * [[WindowHandlerSpec]], [[OutputHandlerSpec]]); the tests here exercise the
 * routing through `handle` for the window-management and output-lifecycle
 * events whose logic lives in `WindowHandler` / `WindowActions` /
 * `OutputHandler` / the `EventHandler` grab setters, plus `handleNewWindow`.
 */
class InputHandlerSpec extends ScalaCheckSuite:

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

  // ── Window lifecycle ───────────────────────────────────────────────

  test("WindowMapped adds window to output active workspace") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(
      InputHandler.handle(InputEvent.WindowMapped(id, out1, Some("w"), None))
    )
    val ws = s2.outputs(out1).active
    assert(ws.windows.get(id).isDefined, s"expected window $id on active workspace, got ${ws.windows.ordered}")
    assertEquals(ws.focus.focused, Some(id))
  }

  test("WindowMapped emits RetileOutput and SetKeyboardFocus") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (_, effects, _) = run(s1)(
      InputHandler.handle(InputEvent.WindowMapped(id, out1, Some("w"), None))
    )
    val seq = effects.toSeq
    assert(seq.contains(ShellEffect.RetileOutput(out1)), s"expected RetileOutput($out1) in $seq")
    assert(seq.contains(ShellEffect.SetKeyboardFocus(id)), s"expected SetKeyboardFocus($id) in $seq")
  }

  test("WindowUnmapped clears mapped flag and transfers focus") {
    val s0 = stateWithOutput
    val (s1, _, idA) = run(s0)(WindowHandler.createWindow(Some("a"), None))
    val (s2, _, idB) = run(s1)(WindowHandler.createWindow(Some("b"), None))
    val (s3, _, _) = run(s2)(WindowHandler.mapWindow(idA, out1, Some("a"), None))
    val (s4, _, _) = run(s3)(WindowHandler.mapWindow(idB, out1, Some("b"), None))
    // B has focus (most recently mapped). Refocus A so unmapping A transfers focus to B.
    val (s5, _, _) = run(s4)(WindowActions.requestFocus(idA))
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
    val (s1, _, idA) = run(s0)(WindowHandler.createWindow(Some("a"), None))
    val (s2, _, idB) = run(s1)(WindowHandler.createWindow(Some("b"), None))
    val (s3, _, _) = run(s2)(WindowHandler.mapWindow(idA, out1, Some("a"), None))
    val (s4, _, _) = run(s3)(WindowHandler.mapWindow(idB, out1, Some("b"), None))
    val (s5, _, _) = run(s4)(WindowActions.requestFocus(idA))
    val (_, effects, _) = run(s5)(
      InputHandler.handle(InputEvent.WindowUnmapped(idA))
    )
    val seq = effects.toSeq
    assert(seq.contains(ShellEffect.RetileOutput(out1)), s"expected RetileOutput($out1) in $seq")
    assert(seq.contains(ShellEffect.SetKeyboardFocus(idB)), s"expected SetKeyboardFocus($idB) in $seq")
  }

  test("WindowDestroyed removes window from state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, _, _) = run(s2)(
      InputHandler.handle(InputEvent.WindowDestroyed(id))
    )
    assertEquals(s3.outputs(out1).active.windows.get(id), None)
  }

  test("WindowDestroyed on window under grab clears grab state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val s3 = s2.copy(grabState = Some(GrabState.Moving(id, 0.0, 0.0, 0, 0)))
    val (s4, _, _) = run(s3)(
      InputHandler.handle(InputEvent.WindowDestroyed(id))
    )
    assertEquals(s4.grabState, None)
  }

  test("RequestMove sets Moving grab state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, _, _) = run(s2)(
      InputHandler.handle(
        InputEvent.RequestMove(id, cursorX = 100.0, cursorY = 200.0, windowX = 50, windowY = 60)
      )
    )
    assertEquals(s3.grabState, Some(GrabState.Moving(id, 100.0, 200.0, 50, 60)))
  }

  test("RequestResize sets Resizing grab state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
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
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
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
