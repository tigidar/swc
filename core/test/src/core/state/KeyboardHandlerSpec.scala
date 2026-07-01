package core.state

import munit.ScalaCheckSuite
import kyo.*
import core.input.*

/**
 * Specification for the pure `KeyboardHandler`.
 *
 * Drives keyboard-device lifecycle (`KeyboardAdded` / `KeyboardRemoved`) and
 * key press / release through `InputHandler.handle` — the realistic runtime
 * path the compositor shell uses — and verifies the `CompositorState` changes
 * (`keyboards`, `activeKeyboard`) and emitted `ShellEffect`s
 * (`SetActiveKeyboard`, `ForwardKeyToClient`, plus bound-keysym actions routed
 * through `KeyActionHandler`).
 */
class KeyboardHandlerSpec extends ScalaCheckSuite:

  private val cfg = CompositorConfig.default
  private val kid  = KeyboardId("kbd-1")
  private val kid2 = KeyboardId("kbd-2")

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
