package core.input

/** A keyboard chord: a specific keysym combined with a modifier mask. */
case class KeyBinding(modifiers: Modifiers, keysym: KeySym)

/** The compositor action to take in response to a key event. */
sealed trait Action
case object SpawnTerminal  extends Action
case object CloseFocused   extends Action
case object ExitCompositor extends Action
/** No binding matched; the event should be forwarded to the focused client. */
case object Passthrough    extends Action

// Master-stack tiling layout controls (awesomewm convention)
case object IncreaseMasterRatio  extends Action
case object DecreaseMasterRatio  extends Action
case object IncreaseMasterCount  extends Action
case object DecreaseMasterCount  extends Action

// Multi-monitor: output focus (Super+F1..F5) and move-to-output (Super+Shift+F1..F5)
case class FocusOutput(index: Int)   extends Action
case class MoveToOutput(index: Int)  extends Action

// Window mode toggles
case object ToggleFloating  extends Action
case object ToggleFullscreen extends Action

// Focus cycling
case object CycleFocus extends Action

// Help
case object ShowKeybindings extends Action

// Launcher
case object OpenLauncher extends Action

// Media keys (brightness, volume)
sealed trait MediaAction extends Action
case object BrightnessUp      extends MediaAction
case object BrightnessDown    extends MediaAction
case object KbdBrightnessUp   extends MediaAction
case object KbdBrightnessDown extends MediaAction
case object VolumeUp          extends MediaAction
case object VolumeDown        extends MediaAction
case object VolumeMute        extends MediaAction
case object MicMute           extends MediaAction

// Gamma
sealed trait GammaAction extends Action
case object GammaUp    extends GammaAction
case object GammaDown  extends GammaAction
case object GammaReset extends GammaAction

// Workspace management (Super+1..9, Super+Shift+1..9)
case class SwitchWorkspace(workspace: Int)     extends Action
case class MoveToWorkspace(workspace: Int)     extends Action

/**
 * A map from keyboard chords to compositor actions.
 *
 * `dispatch` is the pure decision function: given a key event it returns
 * the action to perform. Released events always produce Passthrough so
 * that keybindings are triggered on press only.
 */
case class KeyBindingMap(bindings: Map[KeyBinding, Action]):

  def dispatch(event: KeyEvent): Action =
    if event.state == Released then Passthrough
    else
      val cleaned = Modifiers.stripLocks(event.modifiers)
      bindings.getOrElse(KeyBinding(cleaned, event.keysym), Passthrough)

  def +(binding: KeyBinding, action: Action): KeyBindingMap =
    copy(bindings = bindings + (binding -> action))

object KeyBindingMap:

  // XKB keysym constants
  private inline val XKB_Return = 0xff0d
  private inline val XKB_Escape = 0xff1b
  private inline val XKB_Space  = 0x0020
  private inline val XKB_F1     = 0xffbe
  private inline val XKB_Tab    = 0xff09
  private inline val XKB_F11    = 0xffc8
  private inline val XKB_1      = 0x0031 // '1'

  // XF86 keysym constants (media/laptop keys)
  private inline val XF86_MonBrightnessUp   = 0x1008ff02
  private inline val XF86_MonBrightnessDown = 0x1008ff03
  private inline val XF86_KbdBrightnessUp   = 0x1008ff05
  private inline val XF86_KbdBrightnessDown = 0x1008ff06
  private inline val XF86_AudioRaiseVolume  = 0x1008ff13
  private inline val XF86_AudioLowerVolume  = 0x1008ff11
  private inline val XF86_AudioMute         = 0x1008ff12
  private inline val XF86_AudioMicMute      = 0x1008ffb2

  // Arrow keys
  private inline val XKB_Up    = 0xff52
  private inline val XKB_Down  = 0xff54

  val default: KeyBindingMap =
    var m = Map.empty[KeyBinding, Action]

    // Core compositor bindings
    m += KeyBinding(Modifiers.Super, KeySym(XKB_Return)) -> SpawnTerminal
    m += KeyBinding(Modifiers.Super, KeySym(0x0071))     -> CloseFocused   // Super+Q
    m += KeyBinding(Modifiers.Super, KeySym(XKB_Escape)) -> ExitCompositor

    // Launcher
    m += KeyBinding(Modifiers.Super, KeySym(XKB_Space)) -> OpenLauncher      // Super+Space
    m += KeyBinding(Modifiers.Super, KeySym(0x0072))    -> OpenLauncher      // Super+R

    // Window mode toggles
    m += KeyBinding(Modifiers.Super | Modifiers.Shift, KeySym(XKB_Space)) -> ToggleFloating  // Super+Shift+Space
    m += KeyBinding(Modifiers.Super, KeySym(XKB_F11))   -> ToggleFullscreen  // Super+F11
    m += KeyBinding(Modifiers.Super, KeySym(0x0066))    -> ToggleFullscreen  // Super+F

    // Focus cycling
    m += KeyBinding(Modifiers.Super, KeySym(0x006a))     -> CycleFocus        // Super+J

    // Help overlay
    m += KeyBinding(Modifiers.Super, KeySym(0x0073))    -> ShowKeybindings   // Super+S

    // Master-stack tiling controls
    m += KeyBinding(Modifiers.Super,                   KeySym(0x0068)) -> DecreaseMasterRatio  // Super+H
    m += KeyBinding(Modifiers.Super,                   KeySym(0x006c)) -> IncreaseMasterRatio  // Super+L
    m += KeyBinding(Modifiers.Super | Modifiers.Shift, KeySym(0x0068)) -> DecreaseMasterCount  // Super+Shift+H
    m += KeyBinding(Modifiers.Super | Modifiers.Shift, KeySym(0x006c)) -> IncreaseMasterCount  // Super+Shift+L

    // Output focus: Super+F1..F5 (XKB_KEY_F1 = 0xffbe, F2 = 0xffbf, ...)
    for i <- 0 until 5 do
      m += KeyBinding(Modifiers.Super, KeySym(XKB_F1 + i)) -> FocusOutput(i)

    // Move window to output: Super+Shift+F1..F5
    for i <- 0 until 5 do
      m += KeyBinding(Modifiers.Super | Modifiers.Shift, KeySym(XKB_F1 + i)) -> MoveToOutput(i)

    // Workspace switching: Super+1..9
    for i <- 1 to 9 do
      m += KeyBinding(Modifiers.Super, KeySym(XKB_1 + i - 1)) -> SwitchWorkspace(i)

    // Move window to workspace: Super+Shift+1..9
    for i <- 1 to 9 do
      m += KeyBinding(Modifiers.Super | Modifiers.Shift, KeySym(XKB_1 + i - 1)) -> MoveToWorkspace(i)

    // Laptop function keys (bare keysyms, no modifier)
    m += KeyBinding(Modifiers.None, KeySym(XF86_MonBrightnessUp))   -> BrightnessUp
    m += KeyBinding(Modifiers.None, KeySym(XF86_MonBrightnessDown)) -> BrightnessDown
    m += KeyBinding(Modifiers.None, KeySym(XF86_KbdBrightnessUp))   -> KbdBrightnessUp
    m += KeyBinding(Modifiers.None, KeySym(XF86_KbdBrightnessDown)) -> KbdBrightnessDown
    m += KeyBinding(Modifiers.None, KeySym(XF86_AudioRaiseVolume))  -> VolumeUp
    m += KeyBinding(Modifiers.None, KeySym(XF86_AudioLowerVolume))  -> VolumeDown
    m += KeyBinding(Modifiers.None, KeySym(XF86_AudioMute))         -> VolumeMute
    m += KeyBinding(Modifiers.None, KeySym(XF86_AudioMicMute))      -> MicMute

    // Gamma control: Ctrl+Super+Up/Down to adjust, Ctrl+Super+0 to reset
    m += KeyBinding(Modifiers.Super | Modifiers.Ctrl, KeySym(XKB_Up))   -> GammaUp
    m += KeyBinding(Modifiers.Super | Modifiers.Ctrl, KeySym(XKB_Down)) -> GammaDown
    m += KeyBinding(Modifiers.Super | Modifiers.Ctrl, KeySym(0x0030))   -> GammaReset // Ctrl+Super+0

    KeyBindingMap(m)
