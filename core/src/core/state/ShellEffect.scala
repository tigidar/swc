package core.state

import core.windows.WindowId
import core.output.OutputId
import core.input.KeyboardId

/**
 * Side effects that pure event handling produces as values.
 *
 * The compositor shell interprets these by calling into wlroots / POSIX.
 * Keeping them as an ADT means the pure pipeline is testable on the JVM
 * without any C dependencies.
 */
enum ShellEffect:
  /** Recompute tile geometry for a specific output and apply to windows. */
  case RetileOutput(id: OutputId)
  /** Recompute tile geometry for all outputs. */
  case Retile
  /** Notify the seat that `id` has keyboard focus; activate the toplevel. */
  case SetKeyboardFocus(id: WindowId)
  /** Clear keyboard focus from the seat. */
  case ClearKeyboardFocus
  /** Fork+exec a process with the given argv. */
  case SpawnProcess(args: List[String])
  /** Send a close request to the toplevel for `id`. */
  case CloseWindow(id: WindowId)
  /** Terminate the Wayland display event loop (orderly shutdown). */
  case TerminateDisplay
  /** Show a window's scene node (make visible). */
  case ShowWindow(id: WindowId)
  /** Hide a window's scene node (make invisible). */
  case HideWindow(id: WindowId)
  /** Warp the cursor to a position (used when focusing an output). */
  case WarpCursor(x: Int, y: Int)
  /** Raise a window's scene node to the top of the z-order. */
  case RaiseWindow(id: WindowId)
  /** Configure a toplevel for fullscreen (size to full output area). */
  case SetFullscreen(id: WindowId, x: Int, y: Int, w: Int, h: Int)
  /** Restore a toplevel from fullscreen (retile will set the correct size). */
  case UnsetFullscreen(id: WindowId)
  /** Adjust display backlight by a relative percentage (e.g. +10, -10). */
  case AdjustBrightness(deltaPercent: Int)
  /** Adjust keyboard backlight by a relative step (e.g. +1, -1). */
  case AdjustKbdBrightness(delta: Int)
  /** Set gamma brightness factor on all outputs (0.1 to 1.0). */
  case SetGamma(factor: Float)
  /** Set the idle auto-lock timeout (milliseconds) and re-arm the idle timer. 0 disables. */
  case SetIdleTimeout(ms: Long)
  /** Power all outputs off (DPMS) when `off` is true, or back on when false. No lock/suspend/exit. */
  case SetScreenOff(off: Boolean)
  /** Set the screen-off (DPMS) idle timeout (milliseconds) and re-arm its timer. 0 disables. */
  case SetScreenOffTimeout(ms: Long)
  /** Show the launcher popup widget. */
  case ShowLauncher
  /** Hide the launcher popup widget. */
  case HideLauncher
  /** Update the launcher text display. */
  case UpdateLauncherText(text: String)

  // Keyboard device management
  /** Make `id` the active keyboard for the seat. */
  case SetActiveKeyboard(id: KeyboardId)
  /** Forward a key event to the currently focused client. */
  case ForwardKeyToClient(keyboardId: KeyboardId, time: Long, keycode: Int, pressed: Boolean)

  // Pointer focus management
  /** Notify the seat that the pointer entered `windowId` at surface coords. */
  case PointerEnter(windowId: WindowId, sx: Double, sy: Double)
  /** Deliver a pointer motion event in surface-local coordinates. */
  case PointerMotion(time: Long, sx: Double, sy: Double)
  /** Clear the seat's pointer focus. */
  case ClearPointerFocus
  /** Restore the default cursor image. */
  case SetDefaultCursor

  // Window geometry (from grab computation)
  /** Move a toplevel's scene node to `(x, y)`. */
  case MoveWindow(id: WindowId, x: Int, y: Int)
  /** Resize and reposition a toplevel to the given rect. */
  case ResizeWindow(id: WindowId, x: Int, y: Int, w: Int, h: Int)
