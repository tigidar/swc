package core.state

import munit.FunSuite
import kyo.*
import core.input.*
import core.output.OutputId

class LauncherSpec extends FunSuite:

  private val cfg = CompositorConfig.default
  private val out1 = OutputId("HDMI-A-1")

  private def stateWithOutput: CompositorState =
    val (s, _, _) = EventHandler.run(cfg, CompositorState.empty)(
      EventHandler.addOutput(out1, 1920, 1080, 0, 0)
    )
    s

  private def run[A](state: CompositorState)(pipeline: A < EventHandler.Effects)(using Frame) =
    EventHandler.run(cfg, state)(pipeline)

  // ── Open / close ───────────────────────────────────────────────────

  test("openLauncher sets launcherText to Some empty string") {
    val s0 = stateWithOutput
    val (s1, effects, _) = run(s0)(LauncherHandler.openLauncher)
    assertEquals(s1.launcherText, Some(""))
    assert(effects.toSeq.contains(ShellEffect.ShowLauncher))
  }

  test("openLauncher toggles off when already open") {
    val s0 = stateWithOutput.copy(launcherText = Some("foo"))
    val (s1, effects, _) = run(s0)(LauncherHandler.openLauncher)
    assertEquals(s1.launcherText, None)
    assert(effects.toSeq.contains(ShellEffect.HideLauncher))
  }

  test("closeLauncher clears launcherText") {
    val s0 = stateWithOutput.copy(launcherText = Some("bar"))
    val (s1, effects, _) = run(s0)(LauncherHandler.closeLauncher)
    assertEquals(s1.launcherText, None)
    assert(effects.toSeq.contains(ShellEffect.HideLauncher))
  }

  // ── Character input ────────────────────────────────────────────────

  test("typing characters appends to launcherText") {
    val s0 = stateWithOutput.copy(launcherText = Some(""))
    val (s1, effects1, handled1) = run(s0)(LauncherHandler.handleInput(LauncherInput.Character('f')))
    assertEquals(s1.launcherText, Some("f"))
    assertEquals(handled1, true)
    assert(effects1.toSeq.contains(ShellEffect.UpdateLauncherText("f")))

    val (s2, effects2, _) = run(s1)(LauncherHandler.handleInput(LauncherInput.Character('o')))
    assertEquals(s2.launcherText, Some("fo"))
    assert(effects2.toSeq.contains(ShellEffect.UpdateLauncherText("fo")))
  }

  test("typing 'firefox' produces correct state and effects") {
    var state = stateWithOutput.copy(launcherText = Some(""))
    for ch <- "firefox" do
      val (s, _, _) = run(state)(LauncherHandler.handleInput(LauncherInput.Character(ch)))
      state = s
    assertEquals(state.launcherText, Some("firefox"))
  }

  // ── Backspace ──────────────────────────────────────────────────────

  test("backspace removes last character") {
    val s0 = stateWithOutput.copy(launcherText = Some("foo"))
    val (s1, effects, handled) = run(s0)(LauncherHandler.handleInput(LauncherInput.Backspace))
    assertEquals(s1.launcherText, Some("fo"))
    assertEquals(handled, true)
    assert(effects.toSeq.contains(ShellEffect.UpdateLauncherText("fo")))
  }

  test("backspace on empty string is no-op (stays empty)") {
    val s0 = stateWithOutput.copy(launcherText = Some(""))
    val (s1, effects, handled) = run(s0)(LauncherHandler.handleInput(LauncherInput.Backspace))
    assertEquals(s1.launcherText, Some(""))
    assertEquals(handled, true)
    assert(effects.toSeq.contains(ShellEffect.UpdateLauncherText("")))
  }

  // ── Submit ─────────────────────────────────────────────────────────

  test("submit with text emits SpawnProcess and HideLauncher") {
    val s0 = stateWithOutput.copy(launcherText = Some("htop"))
    val (s1, effects, handled) = run(s0)(LauncherHandler.handleInput(LauncherInput.Submit))
    assertEquals(s1.launcherText, None)
    assertEquals(handled, true)
    val effectSeq = effects.toSeq
    assert(effectSeq.contains(ShellEffect.HideLauncher))
    assert(effectSeq.contains(ShellEffect.SpawnProcess(List("sh", "-c", "htop"))))
  }

  test("submit with empty text just closes, no SpawnProcess") {
    val s0 = stateWithOutput.copy(launcherText = Some(""))
    val (s1, effects, handled) = run(s0)(LauncherHandler.handleInput(LauncherInput.Submit))
    assertEquals(s1.launcherText, None)
    assertEquals(handled, true)
    val effectSeq = effects.toSeq
    assert(effectSeq.contains(ShellEffect.HideLauncher))
    assert(!effectSeq.exists(_.isInstanceOf[ShellEffect.SpawnProcess]))
  }

  // ── Cancel ─────────────────────────────────────────────────────────

  test("cancel closes launcher without spawning") {
    val s0 = stateWithOutput.copy(launcherText = Some("firefox"))
    val (s1, effects, handled) = run(s0)(LauncherHandler.handleInput(LauncherInput.Cancel))
    assertEquals(s1.launcherText, None)
    assertEquals(handled, true)
    val effectSeq = effects.toSeq
    assert(effectSeq.contains(ShellEffect.HideLauncher))
    assert(!effectSeq.exists(_.isInstanceOf[ShellEffect.SpawnProcess]))
  }

  // ── Input when launcher is closed ──────────────────────────────────

  test("handleInput returns false when launcher is closed") {
    val s0 = stateWithOutput // launcherText = None
    val (s1, effects, handled) = run(s0)(LauncherHandler.handleInput(LauncherInput.Character('x')))
    assertEquals(handled, false)
    assertEquals(s1.launcherText, None)
    assert(effects.toSeq.isEmpty)
  }

  // ── Keybinding dispatch ────────────────────────────────────────────

  test("Super+Space dispatches OpenLauncher") {
    val event = KeyEvent(KeySym(0x0020), Modifiers.Super, Pressed) // XKB_Space = 0x0020
    assertEquals(KeyBindingMap.default.dispatch(event), OpenLauncher: Action)
  }

  test("Super+R dispatches OpenLauncher") {
    val event = KeyEvent(KeySym(0x0072), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), OpenLauncher: Action)
  }

  test("Super+Shift+Space dispatches ToggleFloating") {
    val event = KeyEvent(KeySym(0x0020), Modifiers.Super | Modifiers.Shift, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), ToggleFloating: Action)
  }

  // ── handleKey integration ──────────────────────────────────────────

  test("handleKey with OpenLauncher opens launcher") {
    val s0 = stateWithOutput
    val event = KeyEvent(KeySym(0x0020), Modifiers.Super, Pressed) // Super+Space
    val (s1, effects, handled) = run(s0)(EventHandler.handleKey(event))
    assertEquals(handled, true)
    assertEquals(s1.launcherText, Some(""))
    assert(effects.toSeq.contains(ShellEffect.ShowLauncher))
  }
