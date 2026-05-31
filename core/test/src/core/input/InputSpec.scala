package core.input

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.{Arbitrary, Gen}

class InputSpec extends ScalaCheckSuite:

  // ── Arbitrary instances ─────────────────────────────────────────────

  given Arbitrary[KeySym] = Arbitrary(
    Gen.choose(0, Int.MaxValue).map(KeySym(_))
  )

  given Arbitrary[Modifiers] = Arbitrary(
    Gen.choose(0, 255).map(Modifiers.from)
  )

  given Arbitrary[KeyState] = Arbitrary(
    Gen.oneOf(Pressed, Released)
  )

  given Arbitrary[KeyEvent] = Arbitrary(
    for
      sym  <- summon[Arbitrary[KeySym]].arbitrary
      mods <- summon[Arbitrary[Modifiers]].arbitrary
      state <- summon[Arbitrary[KeyState]].arbitrary
    yield KeyEvent(sym, mods, state)
  )

  // ── Modifiers ───────────────────────────────────────────────────────

  test("Modifiers: Super modifier bit is set") {
    assert(Modifiers.Super != Modifiers.None)
  }

  test("Modifiers: combining Super+Shift produces mask with both bits") {
    val combined = Modifiers.Super | Modifiers.Shift
    assert(combined.has(Modifiers.Super))
    assert(combined.has(Modifiers.Shift))
  }

  test("Modifiers: no-modifier mask is 0") {
    assertEquals(Modifiers.None, Modifiers.from(0))
  }

  // ── KeySym ──────────────────────────────────────────────────────────

  test("KeySym: wrap and unwrap roundtrip (opaque type)") {
    val v = 0xff0d
    val ks = KeySym(v)
    assertEquals(KeySym.value(ks), v)
  }

  // ── KeyState ────────────────────────────────────────────────────────

  test("KeyState: Pressed and Released are distinct") {
    assertNotEquals(Pressed: KeyState, Released: KeyState)
  }

  // ── KeyBinding ──────────────────────────────────────────────────────

  test("KeyBinding: equality of two KeyBindings with identical modifiers and keysym") {
    val kb1 = KeyBinding(Modifiers.Super, KeySym(0xff0d))
    val kb2 = KeyBinding(Modifiers.Super, KeySym(0xff0d))
    assertEquals(kb1, kb2)
  }

  // ── Action ──────────────────────────────────────────────────────────

  // ── Modifier properties ─────────────────────────────────────────────

  property("Modifiers.|: commutative") {
    forAll { (a: Modifiers, b: Modifiers) => (a | b) == (b | a) }
  }

  property("Modifiers.|: idempotent with itself") {
    forAll { (a: Modifiers) => (a | a) == a }
  }

  property("Modifiers.has: a.has(b) iff all bits of b are set in a") {
    forAll { (a: Modifiers, b: Modifiers) => a.has(b) == ((a | b) == a) }
  }

  property("Modifiers.stripLocks: removes lock bits but preserves all others") {
    forAll { (a: Modifiers) =>
      val stripped = Modifiers.stripLocks(a)
      !stripped.has(Modifiers.Caps) &&
      !stripped.has(Modifiers.Mod2) &&
      (stripped | Modifiers.LockMask) == (a | Modifiers.LockMask)
    }
  }

  // ── KeyBindingMap.dispatch ──────────────────────────────────────────

  test("KeyBindingMap.dispatch: Super+Return => SpawnTerminal") {
    val event = KeyEvent(KeySym(0xff0d), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), SpawnTerminal: Action)
  }

  test("KeyBindingMap.dispatch: Super+Q => CloseFocused") {
    val event = KeyEvent(KeySym(0x0071), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), CloseFocused: Action)
  }

  test("KeyBindingMap.dispatch: Super+Escape => ExitCompositor") {
    val event = KeyEvent(KeySym(0xff1b), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), ExitCompositor: Action)
  }

  test("KeyBindingMap.dispatch: Super+P (unbound) => Passthrough") {
    val event = KeyEvent(KeySym(0x0070), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), Passthrough: Action)
  }

  test("KeyBindingMap.dispatch: Return without Super => Passthrough (modifier check)") {
    val event = KeyEvent(KeySym(0xff0d), Modifiers.None, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), Passthrough: Action)
  }

  test("KeyBindingMap.dispatch: Released key event => Passthrough (state check)") {
    val event = KeyEvent(KeySym(0xff0d), Modifiers.Super, Released)
    assertEquals(KeyBindingMap.default.dispatch(event), Passthrough: Action)
  }

  property("KeyBindingMap.dispatch is total — no exception for any input") {
    forAll { (sym: KeySym, mods: Modifiers, state: KeyState) =>
      val event = KeyEvent(sym, mods, state)
      KeyBindingMap.default.dispatch(event)
      true
    }
  }

  // ── KeyBindingMap.default ───────────────────────────────────────────

  test("KeyBindingMap.default contains core bindings") {
    val m = KeyBindingMap.default
    assertEquals(m.dispatch(KeyEvent(KeySym(0xff0d), Modifiers.Super, Pressed)), SpawnTerminal: Action)
    assertEquals(m.dispatch(KeyEvent(KeySym(0x0071), Modifiers.Super, Pressed)), CloseFocused: Action)
    assertEquals(m.dispatch(KeyEvent(KeySym(0xff1b), Modifiers.Super, Pressed)), ExitCompositor: Action)
    // Unbound key
    assertEquals(m.dispatch(KeyEvent(KeySym(0x0061), Modifiers.Super, Pressed)), Passthrough: Action)
  }

  test("KeyBindingMap.default contains output focus bindings (Super+F1..F5)") {
    val m = KeyBindingMap.default
    assertEquals(m.dispatch(KeyEvent(KeySym(0xffbe), Modifiers.Super, Pressed)), FocusOutput(0): Action) // F1
    assertEquals(m.dispatch(KeyEvent(KeySym(0xffc2), Modifiers.Super, Pressed)), FocusOutput(4): Action) // F5
  }

  test("KeyBindingMap.default contains workspace bindings (Super+1..9)") {
    val m = KeyBindingMap.default
    assertEquals(m.dispatch(KeyEvent(KeySym(0x0031), Modifiers.Super, Pressed)), SwitchWorkspace(1): Action)
    assertEquals(m.dispatch(KeyEvent(KeySym(0x0039), Modifiers.Super, Pressed)), SwitchWorkspace(9): Action)
  }

  test("KeyBindingMap.default contains move-to-workspace bindings (Super+Shift+1..9)") {
    val m = KeyBindingMap.default
    assertEquals(m.dispatch(KeyEvent(KeySym(0x0031), Modifiers.Super | Modifiers.Shift, Pressed)), MoveToWorkspace(1): Action)
    assertEquals(m.dispatch(KeyEvent(KeySym(0x0039), Modifiers.Super | Modifiers.Shift, Pressed)), MoveToWorkspace(9): Action)
  }

  // ── Brightness keybindings ─────────────────────────────────────────

  test("KeyBindingMap.dispatch: XF86MonBrightnessUp (no modifier) => BrightnessUp") {
    val event = KeyEvent(KeySym(0x1008ff02), Modifiers.None, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), BrightnessUp: Action)
  }

  test("KeyBindingMap.dispatch: XF86MonBrightnessDown (no modifier) => BrightnessDown") {
    val event = KeyEvent(KeySym(0x1008ff03), Modifiers.None, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), BrightnessDown: Action)
  }

  test("KeyBindingMap.dispatch: XF86 brightness keys with modifier => Passthrough") {
    // Brightness keys should only fire without modifiers
    val event = KeyEvent(KeySym(0x1008ff02), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), Passthrough: Action)
  }

  // ── Super+F fullscreen ─────────────────────────────────────────────

  test("KeyBindingMap.dispatch: Super+F => ToggleFullscreen") {
    val event = KeyEvent(KeySym(0x0066), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), ToggleFullscreen: Action)
  }

  test("KeyBindingMap.dispatch: Super+F11 => ToggleFullscreen (original binding)") {
    val event = KeyEvent(KeySym(0xffc8), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), ToggleFullscreen: Action)
  }

  // ── Shift+number raw keysym dispatch ───────────────────────────────
  // These test the keybinding map directly with the RAW keysym (unshifted).
  // In the compositor, handleKeybinding does a two-pass lookup:
  //   1st: modified keysym (Shift+1 = '!' = 0x0021) — won't match
  //   2nd: raw keysym ('1' = 0x0031) — matches MoveToWorkspace

  test("KeyBindingMap.dispatch: Shift+1 modified keysym '!' => Passthrough (won't match)") {
    // '!' is 0x0021 — the shifted version of '1'
    val event = KeyEvent(KeySym(0x0021), Modifiers.Super | Modifiers.Shift, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), Passthrough: Action)
  }

  test("KeyBindingMap.dispatch: Shift+1 raw keysym '1' => MoveToWorkspace(1)") {
    // The raw/unshifted keysym '1' (0x0031) with Super+Shift matches
    val event = KeyEvent(KeySym(0x0031), Modifiers.Super | Modifiers.Shift, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), MoveToWorkspace(1): Action)
  }

  // ── CycleFocus ─────────────────────────────────────────────────────

  test("KeyBindingMap.dispatch: Super+J => CycleFocus") {
    val event = KeyEvent(KeySym(0x006a), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), CycleFocus: Action)
  }
