package core.state

import munit.FunSuite
import kyo.*
import core.windows.WindowId
import core.input.*
import core.output.{OutputId, WorkspaceId}

class EventHandlerSpec extends FunSuite:

  private val cfg = CompositorConfig.default
  private val out1 = OutputId("HDMI-A-1")

  /** Create a state with one output (auto-focused), workspace 1 active. */
  private def stateWithOutput: CompositorState =
    val (s, _, _) = EventHandler.run(cfg, CompositorState.empty)(
      EventHandler.addOutput(out1, 1920, 1080, 0, 0)
    )
    s

  private def run[A](state: CompositorState)(pipeline: A < EventHandler.Effects)(using Frame) =
    EventHandler.run(cfg, state)(pipeline)

  // ── createWindow + mapWindow ──────────────────────────────────────

  test("createWindow assigns sequential IDs") {
    val s0 = stateWithOutput
    val (s1, _, id1) = run(s0)(EventHandler.createWindow(Some("first"), None))
    val (s2, _, id2) = run(s1)(EventHandler.createWindow(Some("second"), None))
    assertEquals(WindowId.value(id1), 0L)
    assertEquals(WindowId.value(id2), 1L)
  }

  test("mapWindow adds window to output's active workspace") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val ws = s2.outputs(out1).active
    assert(ws.windows.get(id).isDefined)
    assertEquals(ws.windows.get(id).get.mapped, true)
    assertEquals(ws.focus.focused, Some(id))
  }

  test("mapWindow emits RetileOutput and SetKeyboardFocus") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (_, effects, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val effectSeq = effects.toSeq
    assert(effectSeq.contains(ShellEffect.RetileOutput(out1)), s"expected RetileOutput in $effectSeq")
    assert(effectSeq.contains(ShellEffect.SetKeyboardFocus(id)), s"expected SetKeyboardFocus in $effectSeq")
  }

  // ── unmapWindow ─────────────────────────────────────────────────────

  test("unmapWindow clears mapped flag and transfers focus") {
    val s0 = stateWithOutput
    val (s1, _, id1) = run(s0)(EventHandler.createWindow(Some("a"), None))
    val (s2, _, id2) = run(s1)(EventHandler.createWindow(Some("b"), None))
    val (s3, _, _) = run(s2)(EventHandler.mapWindow(id1, out1, Some("a"), None))
    val (s4, _, _) = run(s3)(EventHandler.mapWindow(id2, out1, Some("b"), None))
    val (s5, _, _) = run(s4)(EventHandler.unmapWindow(id2))
    val ws = s5.outputs(out1).active
    assertEquals(ws.windows.get(id2).get.mapped, false)
    assertEquals(ws.focus.focused, Some(id1))
  }

  test("unmapWindow emits RetileOutput") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val (_, effects, _) = run(s2)(EventHandler.unmapWindow(id))
    assert(effects.toSeq.exists(_.isInstanceOf[ShellEffect.RetileOutput]))
  }

  // ── destroyWindow ──────────────────────────────────────────────────

  test("destroyWindow removes window from workspace") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, _, _) = run(s2)(EventHandler.destroyWindow(id))
    assertEquals(s3.outputs(out1).active.windows.get(id), None)
  }

  test("destroyWindow cancels grab on that window") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val grab = GrabState.Moving(id, 0.0, 0.0, 0, 0)
    val s3 = s2.copy(grabState = Some(grab))
    val (s4, _, _) = run(s3)(EventHandler.destroyWindow(id))
    assertEquals(s4.grabState, None)
  }

  // ── handleKey ──────────────────────────────────────────────────────

  test("handleKey SpawnTerminal emits SpawnProcess with terminal from config") {
    val customCfg = CompositorConfig.default.copy(terminalCmd = "alacritty")
    val s0 = stateWithOutput
    val superReturn = KeyEvent(KeySym(0xff0d), Modifiers.Super, Pressed)
    val (_, effects, handled) = EventHandler.run(customCfg, s0)(EventHandler.handleKey(superReturn))
    assert(handled)
    assert(effects.toSeq.contains(ShellEffect.SpawnProcess(List("alacritty"))))
  }

  test("handleKey CloseFocused with focused window emits CloseWindow") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val superQ = KeyEvent(KeySym(0x0071), Modifiers.Super, Pressed)
    val (_, effects, handled) = run(s2)(EventHandler.handleKey(superQ))
    assert(handled)
    assert(effects.toSeq.contains(ShellEffect.CloseWindow(id)))
  }

  test("handleKey ExitCompositor emits TerminateDisplay") {
    val s0 = stateWithOutput
    val superEsc = KeyEvent(KeySym(0xff1b), Modifiers.Super, Pressed)
    val (_, effects, handled) = run(s0)(EventHandler.handleKey(superEsc))
    assert(handled)
    assert(effects.toSeq.contains(ShellEffect.TerminateDisplay))
  }

  test("handleKey Passthrough returns false and no effects") {
    val s0 = stateWithOutput
    val plainA = KeyEvent(KeySym(0x0061), Modifiers.None, Pressed)
    val (_, effects, handled) = run(s0)(EventHandler.handleKey(plainA))
    assert(!handled)
    assert(effects.isEmpty)
  }

  // ── Output management ──────────────────────────────────────────────

  test("addOutput creates output with 9 empty workspaces") {
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(EventHandler.addOutput(out1, 1920, 1080, 0, 0))
    assert(s1.outputs.contains(out1))
    assertEquals(s1.outputs(out1).workspaces.size, 9)
    assertEquals(s1.outputs(out1).activeWorkspace, WorkspaceId(1))
  }

  test("first output is auto-focused") {
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(EventHandler.addOutput(out1, 1920, 1080, 0, 0))
    assertEquals(s1.focusedOutput, Some(out1))
  }

  test("second output does not steal focus") {
    val out2 = OutputId("DP-1")
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(EventHandler.addOutput(out1, 1920, 1080, 0, 0))
    val (s2, _, _) = run(s1)(EventHandler.addOutput(out2, 1920, 1080, 1920, 0))
    assertEquals(s2.focusedOutput, Some(out1))
  }

  // ── Workspace switching ────────────────────────────────────────────

  test("switchWorkspace changes active workspace on focused output") {
    val s0 = stateWithOutput
    assertEquals(s0.outputs(out1).activeWorkspace, WorkspaceId(1))
    val (s1, _, _) = run(s0)(EventHandler.switchWorkspace(WorkspaceId(3)))
    assertEquals(s1.outputs(out1).activeWorkspace, WorkspaceId(3))
  }

  test("switchWorkspace emits HideWindow for old workspace windows") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val (_, effects, _) = run(s2)(EventHandler.switchWorkspace(WorkspaceId(2)))
    val effectSeq = effects.toSeq
    assert(effectSeq.contains(ShellEffect.HideWindow(id)), s"expected HideWindow in $effectSeq")
  }

  test("moveWindowToWorkspace moves focused window and hides it") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, effects, _) = run(s2)(EventHandler.moveWindowToWorkspace(WorkspaceId(2)))
    assertEquals(s3.outputs(out1).workspaces(WorkspaceId(1)).windows.get(id), None)
    assert(s3.outputs(out1).workspaces(WorkspaceId(2)).windows.get(id).isDefined)
    assert(effects.toSeq.contains(ShellEffect.HideWindow(id)))
  }

  // ── Move to output ─────────────────────────────────────────────────

  test("moveWindowToOutputByIndex moves window between outputs") {
    val out2 = OutputId("DP-1")
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(EventHandler.addOutput(out1, 1920, 1080, 0, 0))
    val (s2, _, _) = run(s1)(EventHandler.addOutput(out2, 1920, 1080, 1920, 0))
    val (s3, _, id) = run(s2)(EventHandler.createWindow(Some("w"), None))
    val (s4, _, _) = run(s3)(EventHandler.mapWindow(id, out1, Some("w"), None))
    // Window is on out1 workspace 1. Move to output index 1 (out2)
    val (s5, effects, _) = run(s4)(EventHandler.moveWindowToOutputByIndex(1))
    // Gone from out1
    assertEquals(s5.outputs(out1).active.windows.get(id), None)
    // Present on out2
    assert(s5.outputs(out2).active.windows.get(id).isDefined)
    // Both outputs retiled
    assert(effects.toSeq.contains(ShellEffect.RetileOutput(out1)))
    assert(effects.toSeq.contains(ShellEffect.RetileOutput(out2)))
  }

  test("Super+Shift+F2 moves focused window to second output") {
    val out2 = OutputId("DP-1")
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(EventHandler.addOutput(out1, 1920, 1080, 0, 0))
    val (s2, _, _) = run(s1)(EventHandler.addOutput(out2, 1920, 1080, 1920, 0))
    val (s3, _, id) = run(s2)(EventHandler.createWindow(Some("w"), None))
    val (s4, _, _) = run(s3)(EventHandler.mapWindow(id, out1, Some("w"), None))
    // Super+Shift+F2 = MoveToOutput(1)
    val superShiftF2 = KeyEvent(KeySym(0xffbf), Modifiers.Super | Modifiers.Shift, Pressed)
    val (s5, _, handled) = run(s4)(EventHandler.handleKey(superShiftF2))
    assert(handled)
    assertEquals(s5.outputs(out1).active.windows.get(id), None)
    assert(s5.outputs(out2).active.windows.get(id).isDefined)
  }

  // ── Focus ──────────────────────────────────────────────────────────

  test("requestFocus sets focus in the correct workspace") {
    val s0 = stateWithOutput
    val (s1, _, id1) = run(s0)(EventHandler.createWindow(Some("a"), None))
    val (s2, _, id2) = run(s1)(EventHandler.createWindow(Some("b"), None))
    val (s3, _, _) = run(s2)(EventHandler.mapWindow(id1, out1, Some("a"), None))
    val (s4, _, _) = run(s3)(EventHandler.mapWindow(id2, out1, Some("b"), None))
    val (s5, effects, _) = run(s4)(EventHandler.requestFocus(id1))
    val ws = s5.outputs(out1).active
    assertEquals(ws.focus.focused, Some(id1))
    assert(effects.toSeq.contains(ShellEffect.SetKeyboardFocus(id1)))
  }

  // ── Grab state ─────────────────────────────────────────────────────

  test("beginGrab sets grab state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val grab = GrabState.Moving(id, 100.0, 200.0, 10, 20)
    val (s2, _, _) = run(s1)(EventHandler.beginGrab(grab))
    assertEquals(s2.grabState, Some(grab))
  }

  test("releaseGrab clears grab state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val grab = GrabState.Moving(id, 100.0, 200.0, 10, 20)
    val s2 = s1.copy(grabState = Some(grab))
    val (s3, _, _) = run(s2)(EventHandler.releaseGrab)
    assertEquals(s3.grabState, None)
  }

  // ── Keybinding: output focus ────────────────────────────────────────

  test("Super+F1 focuses first output, Super+F2 focuses second") {
    val out2 = OutputId("DP-1")
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(EventHandler.addOutput(out1, 1920, 1080, 0, 0))
    val (s2, _, _) = run(s1)(EventHandler.addOutput(out2, 1920, 1080, 1920, 0))
    assertEquals(s2.focusedOutput, Some(out1)) // first output auto-focused

    // Super+F2 (0xffbf) focuses second output
    val superF2 = KeyEvent(KeySym(0xffbf), Modifiers.Super, Pressed)
    val (s3, effects, handled) = run(s2)(EventHandler.handleKey(superF2))
    assert(handled)
    assertEquals(s3.focusedOutput, Some(out2))
    assert(effects.toSeq.exists(_.isInstanceOf[ShellEffect.WarpCursor]))

    // Super+F1 (0xffbe) goes back to first
    val superF1 = KeyEvent(KeySym(0xffbe), Modifiers.Super, Pressed)
    val (s4, _, _) = run(s3)(EventHandler.handleKey(superF1))
    assertEquals(s4.focusedOutput, Some(out1))
  }

  // ── Keybinding: workspace switching ───────────────────────────────

  test("Super+2 switches to workspace 2 on focused output") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))
    assertEquals(s2.outputs(out1).activeWorkspace, WorkspaceId(1))

    // Super+2 (0x0032)
    val super2 = KeyEvent(KeySym(0x0032), Modifiers.Super, Pressed)
    val (s3, effects, handled) = run(s2)(EventHandler.handleKey(super2))
    assert(handled)
    assertEquals(s3.outputs(out1).activeWorkspace, WorkspaceId(2))
    // Window on workspace 1 should be hidden
    assert(effects.toSeq.contains(ShellEffect.HideWindow(id)))
  }

  test("Super+Shift+3 moves focused window to workspace 3") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(EventHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(EventHandler.mapWindow(id, out1, Some("w"), None))

    // Super+Shift+3 (0x0033)
    val superShift3 = KeyEvent(KeySym(0x0033), Modifiers.Super | Modifiers.Shift, Pressed)
    val (s3, effects, handled) = run(s2)(EventHandler.handleKey(superShift3))
    assert(handled)
    // Window should be on workspace 3 now
    assertEquals(s3.outputs(out1).workspaces(WorkspaceId(1)).windows.get(id), None)
    assert(s3.outputs(out1).workspaces(WorkspaceId(3)).windows.get(id).isDefined)
    assert(effects.toSeq.contains(ShellEffect.HideWindow(id)))
  }

  // ── Pipeline composition ───────────────────────────────────────────

  test("multiple operations compose in a single pipeline") {
    val s0 = stateWithOutput
    val (s1, effects, (id1, id2)) = run(s0) {
      for
        a <- EventHandler.createWindow(Some("first"), None)
        b <- EventHandler.createWindow(Some("second"), None)
        _ <- EventHandler.mapWindow(a, out1, Some("first"), None)
        _ <- EventHandler.mapWindow(b, out1, Some("second"), None)
      yield (a, b)
    }
    val ws = s1.outputs(out1).active
    assertEquals(ws.windows.size, 2)
    assertEquals(ws.focus.focused, Some(id2))
    assertEquals(effects.toSeq.count(_.isInstanceOf[ShellEffect.RetileOutput]), 2)
  }
