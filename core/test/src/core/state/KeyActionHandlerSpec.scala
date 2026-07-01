package core.state

import munit.FunSuite
import core.input.*
import core.output.{OutputId, WorkspaceId}

class KeyActionHandlerSpec extends FunSuite, StateHarness:

  test("handleKey SpawnTerminal emits SpawnProcess with terminal from config") {
    val customCfg = CompositorConfig.default.copy(terminalCmd = "alacritty")
    val s0 = stateWithOutput
    val superReturn = KeyEvent(KeySym(0xff0d), Modifiers.Super, Pressed)
    val (_, effects, handled) = EventHandler.run(customCfg, s0)(KeyActionHandler.handleKey(superReturn))
    assert(handled)
    assert(effects.toSeq.contains(ShellEffect.SpawnProcess(List("alacritty"))))
  }

  test("handleKey CloseFocused with focused window emits CloseWindow") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val superQ = KeyEvent(KeySym(0x0071), Modifiers.Super, Pressed)
    val (_, effects, handled) = run(s2)(KeyActionHandler.handleKey(superQ))
    assert(handled)
    assert(effects.toSeq.contains(ShellEffect.CloseWindow(id)))
  }

  test("handleKey ExitCompositor emits TerminateDisplay") {
    val s0 = stateWithOutput
    val superEsc = KeyEvent(KeySym(0xff1b), Modifiers.Super, Pressed)
    val (_, effects, handled) = run(s0)(KeyActionHandler.handleKey(superEsc))
    assert(handled)
    assert(effects.toSeq.contains(ShellEffect.TerminateDisplay))
  }

  test("handleKey Passthrough returns false and no effects") {
    val s0 = stateWithOutput
    val plainA = KeyEvent(KeySym(0x0061), Modifiers.None, Pressed)
    val (_, effects, handled) = run(s0)(KeyActionHandler.handleKey(plainA))
    assert(!handled)
    assert(effects.isEmpty)
  }

  // ── Keybinding: move window to output ───────────────────────────────

  test("Super+Shift+F2 moves focused window to second output") {
    val out2 = OutputId("DP-1")
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(OutputHandler.addOutput(out1, 1920, 1080, 0, 0))
    val (s2, _, _) = run(s1)(OutputHandler.addOutput(out2, 1920, 1080, 1920, 0))
    val (s3, _, id) = run(s2)(WindowHandler.createWindow(Some("w"), None))
    val (s4, _, _) = run(s3)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    // Super+Shift+F2 = MoveToOutput(1)
    val superShiftF2 = KeyEvent(KeySym(0xffbf), Modifiers.Super | Modifiers.Shift, Pressed)
    val (s5, _, handled) = run(s4)(KeyActionHandler.handleKey(superShiftF2))
    assert(handled)
    assertEquals(s5.outputs(out1).active.windows.get(id), None)
    assert(s5.outputs(out2).active.windows.get(id).isDefined)
  }

  // ── Keybinding: output focus ────────────────────────────────────────

  test("Super+F1 focuses first output, Super+F2 focuses second") {
    val out2 = OutputId("DP-1")
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(OutputHandler.addOutput(out1, 1920, 1080, 0, 0))
    val (s2, _, _) = run(s1)(OutputHandler.addOutput(out2, 1920, 1080, 1920, 0))
    assertEquals(s2.focusedOutput, Some(out1)) // first output auto-focused

    // Super+F2 (0xffbf) focuses second output
    val superF2 = KeyEvent(KeySym(0xffbf), Modifiers.Super, Pressed)
    val (s3, effects, handled) = run(s2)(KeyActionHandler.handleKey(superF2))
    assert(handled)
    assertEquals(s3.focusedOutput, Some(out2))
    assert(effects.toSeq.exists(_.isInstanceOf[ShellEffect.WarpCursor]))

    // Super+F1 (0xffbe) goes back to first
    val superF1 = KeyEvent(KeySym(0xffbe), Modifiers.Super, Pressed)
    val (s4, _, _) = run(s3)(KeyActionHandler.handleKey(superF1))
    assertEquals(s4.focusedOutput, Some(out1))
  }

  // ── Keybinding: workspace switching ───────────────────────────────

  test("Super+2 switches to workspace 2 on focused output") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    assertEquals(s2.outputs(out1).activeWorkspace, WorkspaceId(1))

    // Super+2 (0x0032)
    val super2 = KeyEvent(KeySym(0x0032), Modifiers.Super, Pressed)
    val (s3, effects, handled) = run(s2)(KeyActionHandler.handleKey(super2))
    assert(handled)
    assertEquals(s3.outputs(out1).activeWorkspace, WorkspaceId(2))
    // Window on workspace 1 should be hidden
    assert(effects.toSeq.contains(ShellEffect.HideWindow(id)))
  }

  test("Super+Shift+3 moves focused window to workspace 3") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))

    // Super+Shift+3 (0x0033)
    val superShift3 = KeyEvent(KeySym(0x0033), Modifiers.Super | Modifiers.Shift, Pressed)
    val (s3, effects, handled) = run(s2)(KeyActionHandler.handleKey(superShift3))
    assert(handled)
    // Window should be on workspace 3 now
    assertEquals(s3.outputs(out1).workspaces(WorkspaceId(1)).windows.get(id), None)
    assert(s3.outputs(out1).workspaces(WorkspaceId(3)).windows.get(id).isDefined)
    assert(effects.toSeq.contains(ShellEffect.HideWindow(id)))
  }
