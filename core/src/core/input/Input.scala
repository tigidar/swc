package core.input

/**
 * Keyboard modifier bitmask.
 * `Modifiers.None` is zero (no modifiers held).
 * Individual modifier bits can be combined with `|`.
 */
opaque type Modifiers = Int

object Modifiers:
  val None: Modifiers  = 0
  val Shift: Modifiers = 1
  val Caps: Modifiers  = 2    // WLR_MODIFIER_CAPS (Caps Lock)
  val Ctrl: Modifiers  = 4
  val Alt: Modifiers   = 8
  val Mod2: Modifiers  = 16   // WLR_MODIFIER_MOD2 (Num Lock)
  val Super: Modifiers = 64   // WLR_MODIFIER_LOGO

  /** Lock modifiers that should be ignored when matching keybindings. */
  val LockMask: Modifiers = Caps | Mod2

  /** Wrap a raw bitmask received from the shell / input layer. */
  def from(raw: Int): Modifiers = raw

  /** Strip lock modifiers (Caps Lock, Num Lock) for keybinding matching. */
  def stripLocks(m: Modifiers): Modifiers = m & ~LockMask

  extension (self: Modifiers)
    def |(other: Modifiers): Modifiers = self | other
    def &(other: Modifiers): Modifiers = self & other
    def unary_~ : Modifiers            = ~self
    def has(m: Modifiers): Boolean     = (self & m) == m

/**
 * An XKB keysym value.
 * Opaque wrapper around Int so that raw integers cannot be passed
 * where a KeySym is required.
 */
opaque type KeySym = Int

object KeySym:
  def apply(v: Int): KeySym       = v
  def value(k: KeySym): Int       = k

/** Whether a key event is a press or a release. */
sealed trait KeyState
case object Pressed  extends KeyState
case object Released extends KeyState

/** A single keyboard event: which key, which modifiers, press or release. */
case class KeyEvent(keysym: KeySym, modifiers: Modifiers, state: KeyState)
