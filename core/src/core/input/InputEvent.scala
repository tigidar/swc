package core.input

import core.geometry.Rect
import core.output.OutputId
import core.windows.WindowId

/**
 * Algebra of input events produced by the compositor shell and consumed by
 * the pure `InputHandler` pipeline. Each variant carries the primitive data
 * that the shell has extracted from wlroots, so the pure model can decide
 * what `ShellEffect`s to emit and how to update `CompositorState` without
 * any C or FFI access.
 */
sealed trait InputEvent

object InputEvent:

  // Output lifecycle

  case class OutputAdded(
    id:      OutputId,
    width:   Int,
    height:  Int,
    layoutX: Int,
    layoutY: Int
  ) extends InputEvent

  case class OutputRemoved(id: OutputId) extends InputEvent

  case class UsableAreaChanged(id: OutputId, area: Rect) extends InputEvent

  // Keyboard device lifecycle

  case class KeyboardAdded(id: KeyboardId)   extends InputEvent
  case class KeyboardRemoved(id: KeyboardId) extends InputEvent

  // Key events — compositor has already resolved keysyms and modifiers.

  case class KeyPress(
    keyboardId: KeyboardId,
    keysym:     KeySym,
    rawKeysym:  KeySym,
    modifiers:  Modifiers,
    keycode:    Int,
    time:       Long
  ) extends InputEvent

  case class KeyRelease(
    keyboardId: KeyboardId,
    keycode:    Int,
    time:       Long
  ) extends InputEvent

  // Window lifecycle

  case class WindowCreated(
    id:    WindowId,
    title: Option[String],
    appId: Option[String]
  ) extends InputEvent

  case class WindowMapped(
    id:       WindowId,
    outputId: OutputId,
    title:    Option[String],
    appId:    Option[String]
  ) extends InputEvent

  case class WindowUnmapped(id: WindowId)   extends InputEvent
  case class WindowDestroyed(id: WindowId)  extends InputEvent

  // Client-requested window operations

  case class RequestMove(
    id:      WindowId,
    cursorX: Double,
    cursorY: Double,
    windowX: Int,
    windowY: Int
  ) extends InputEvent

  case class RequestResize(
    id:      WindowId,
    edges:   Int,
    cursorX: Double,
    cursorY: Double,
    windowX: Int,
    windowY: Int,
    windowW: Int,
    windowH: Int
  ) extends InputEvent

  case class RequestFullscreen(id: WindowId) extends InputEvent

  // Pointer events — PointerFocus already resolved by the shell via hit-test.

  case class PointerMoved(
    cursorX: Double,
    cursorY: Double,
    time:    Long,
    focus:   PointerFocus
  ) extends InputEvent

  case class PointerButton(
    button:  Int,
    pressed: Boolean,
    time:    Long,
    cursorX: Double,
    cursorY: Double,
    focus:   PointerFocus
  ) extends InputEvent
