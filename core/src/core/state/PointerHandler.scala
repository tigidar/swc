package core.state

import kyo.*
import core.input.*

/**
 * Pure pointer-event interpreter.
 *
 * Owns the pointer domain that [[InputHandler.handle]] dispatches to:
 * cursor motion (with grab-aware move/resize), pointer focus enter/motion,
 * and button press/release (focus-on-click and grab release). Reads and
 * writes `cursorX/Y`, `pointerFocus`, and `grabState` on `CompositorState`
 * and emits the matching `ShellEffect`s; the keyboard domain lives next door
 * in [[KeyboardHandler]].
 */
object PointerHandler:

  type Effects = EventHandler.Effects

  def moved(e: InputEvent.PointerMoved)(using Frame): Unit < Effects =
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

  def button(e: InputEvent.PointerButton)(using Frame): Unit < Effects =
    for
      s <- Var.get[CompositorState]
      _ <- (e.pressed, e.focus) match
        case (true, Focus(wid, _)) if s.grabState.isEmpty => WindowActions.requestFocus(wid)
        case (false, _) if s.grabState.isDefined          => EventHandler.releaseGrab
        case _                                            => ((): Unit < Effects)
    yield ()
