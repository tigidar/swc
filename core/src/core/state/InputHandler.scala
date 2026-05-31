package core.state

import kyo.*
import core.input.*
import core.output.*
import core.windows.*
import core.geometry.*

/**
 * Pure `InputEvent` interpreter.
 *
 * The compositor shell extracts primitives from wlroots, constructs an
 * `InputEvent`, and passes it to [[handle]] via `EventHandler.run`.
 * `handle` updates `CompositorState` and emits `ShellEffect`s describing
 * the work the shell must perform on wlroots. This keeps every callback
 * pure and JVM-testable.
 */
object InputHandler:

  type Effects = EventHandler.Effects

  /** Dispatch an `InputEvent` to its handler. */
  def handle(event: InputEvent)(using Frame): Unit < Effects =
    event match
      // Output lifecycle — delegate to EventHandler
      case InputEvent.OutputAdded(id, width, height, layoutX, layoutY) =>
        EventHandler.addOutput(id, width, height, layoutX, layoutY)
      case InputEvent.OutputRemoved(id)       => EventHandler.removeOutput(id)
      case InputEvent.UsableAreaChanged(id, area) =>
        EventHandler.updateUsableArea(id, area)

      // Keyboard device lifecycle
      case InputEvent.KeyboardAdded(id)     => handleKeyboardAdded(id)
      case InputEvent.KeyboardRemoved(id)   => handleKeyboardRemoved(id)

      // Key events
      case e: InputEvent.KeyPress           => handleKeyPress(e)
      case e: InputEvent.KeyRelease         => handleKeyRelease(e)

      // Window lifecycle — delegate to EventHandler
      case _: InputEvent.WindowCreated      => ((): Unit < Effects)
      case InputEvent.WindowMapped(id, oid, title, appId) =>
        EventHandler.mapWindow(id, oid, title, appId)
      case InputEvent.WindowUnmapped(id)    => EventHandler.unmapWindow(id)
      case InputEvent.WindowDestroyed(id)   => EventHandler.destroyWindow(id)
      case InputEvent.RequestMove(id, cx, cy, wx, wy) =>
        EventHandler.beginGrab(GrabState.Moving(id, cx, cy, wx, wy))
      case InputEvent.RequestResize(id, edges, cx, cy, wx, wy, ww, wh) =>
        EventHandler.beginGrab(GrabState.Resizing(id, cx, cy, wx, wy, ww, wh, edges))
      case _: InputEvent.RequestFullscreen  => EventHandler.toggleFullscreen

      // Pointer events
      case e: InputEvent.PointerMoved       => handlePointerMoved(e)
      case e: InputEvent.PointerButton      => handlePointerButton(e)

  /** Allocate a new `WindowId`. Delegates to `EventHandler.createWindow`. */
  def handleNewWindow(title: Option[String], appId: Option[String])(using Frame): WindowId < Effects =
    EventHandler.createWindow(title, appId)

  // ── Keyboard device lifecycle ───────────────────────────────────────

  private def handleKeyboardAdded(id: KeyboardId)(using Frame): Unit < Effects =
    for
      s <- Var.get[CompositorState]
      newKeyboards  = if s.keyboards.contains(id) then s.keyboards else s.keyboards :+ id
      becomesActive = s.activeKeyboard.isEmpty
      _ <- Var.set(s.copy(keyboards = newKeyboards, activeKeyboard = s.activeKeyboard.orElse(Some(id))))
      _ <- if becomesActive then Emit.value(ShellEffect.SetActiveKeyboard(id))
           else ((): Unit < Emit[ShellEffect])
    yield ()

  private def handleKeyboardRemoved(id: KeyboardId)(using Frame): Unit < Effects =
    for
      s <- Var.get[CompositorState]
      newKeyboards = s.keyboards.filterNot(_ == id)
      newActive = s.activeKeyboard match
        case Some(aid) if aid == id => newKeyboards.headOption
        case other                  => other
      _ <- Var.set(s.copy(keyboards = newKeyboards, activeKeyboard = newActive))
    yield ()

  // ── Key events ──────────────────────────────────────────────────────

  private def handleKeyPress(e: InputEvent.KeyPress)(using Frame): Unit < Effects =
    for
      _ <- markActiveKeyboard(e.keyboardId)
      s <- Var.get[CompositorState]
      _ <- s.launcherText match
        case Some(_) =>
          // Launcher is open — the shell routes keys into LauncherHandler
          // imperatively (keysym → LauncherInput conversion needs XKB/FFI).
          // Do not emit ForwardKeyToClient so the key is swallowed here.
          ((): Unit < Effects)
        case None =>
          for
            h1 <- EventHandler.handleKey(KeyEvent(e.keysym, e.modifiers, Pressed))
            h2 <- if h1 then (true: Boolean < Effects)
                  else EventHandler.handleKey(KeyEvent(e.rawKeysym, e.modifiers, Pressed))
            _  <- if h2 then ((): Unit < Effects)
                  else Emit.value(
                    ShellEffect.ForwardKeyToClient(e.keyboardId, e.time, e.keycode, true)
                  )
          yield ()
    yield ()

  private def handleKeyRelease(e: InputEvent.KeyRelease)(using Frame): Unit < Effects =
    Emit.value(ShellEffect.ForwardKeyToClient(e.keyboardId, e.time, e.keycode, false))

  private def markActiveKeyboard(id: KeyboardId)(using Frame): Unit < Effects =
    for
      s <- Var.get[CompositorState]
      _ <- Var.set(s.copy(activeKeyboard = Some(id)))
      _ <- Emit.value(ShellEffect.SetActiveKeyboard(id))
    yield ()

  // ── Pointer events ──────────────────────────────────────────────────

  private def handlePointerMoved(e: InputEvent.PointerMoved)(using Frame): Unit < Effects =
    for
      s <- Var.get[CompositorState]
      _ <- s.grabState match
        case Some(grab: GrabState.Moving) =>
          for
            _ <- Var.set(s.copy(cursorX = e.cursorX, cursorY = e.cursorY))
            (x, y) = GrabState.computeMove(grab, e.cursorX, e.cursorY)
            _ <- Emit.value(ShellEffect.MoveWindow(grab.id, x, y))
          yield ()
        case Some(grab: GrabState.Resizing) =>
          for
            _ <- Var.set(s.copy(cursorX = e.cursorX, cursorY = e.cursorY))
            r = GrabState.computeResize(grab, e.cursorX, e.cursorY)
            _ <- Emit.value(ShellEffect.ResizeWindow(grab.id, r.x, r.y, r.w, r.h))
          yield ()
        case None =>
          e.focus match
            case Focus(wid, surfacePos) =>
              if s.pointerFocus.contains(wid) then
                for
                  _ <- Var.set(s.copy(cursorX = e.cursorX, cursorY = e.cursorY))
                  _ <- Emit.value(ShellEffect.PointerMotion(e.time, surfacePos.x, surfacePos.y))
                yield ()
              else
                for
                  _ <- Var.set(s.copy(
                    cursorX      = e.cursorX,
                    cursorY      = e.cursorY,
                    pointerFocus = Some(wid)
                  ))
                  _ <- Emit.value(ShellEffect.PointerEnter(wid, surfacePos.x, surfacePos.y))
                yield ()
            case NoFocus =>
              for
                _ <- Var.set(s.copy(
                  cursorX      = e.cursorX,
                  cursorY      = e.cursorY,
                  pointerFocus = None
                ))
                _ <- Emit.value(ShellEffect.ClearPointerFocus)
                _ <- Emit.value(ShellEffect.SetDefaultCursor)
              yield ()
    yield ()

  private def handlePointerButton(e: InputEvent.PointerButton)(using Frame): Unit < Effects =
    for
      s <- Var.get[CompositorState]
      _ <- (e.pressed, e.focus) match
        case (true, Focus(wid, _)) if s.grabState.isEmpty => EventHandler.requestFocus(wid)
        case (false, _) if s.grabState.isDefined          => EventHandler.releaseGrab
        case _                                            => ((): Unit < Effects)
    yield ()
