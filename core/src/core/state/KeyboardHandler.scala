package core.state

import kyo.*
import core.input.*

/**
 * Pure keyboard-event interpreter.
 *
 * Owns the keyboard domain that [[InputHandler.handle]] dispatches to:
 * keyboard-device lifecycle (`added` / `removed`, tracking the active
 * keyboard) and key routing (`keyPress` runs bound keysym → action through
 * [[KeyActionHandler]], swallowing keys while the launcher is open and
 * otherwise forwarding unbound keys to the focused client; `keyRelease`
 * forwards). The pointer domain lives next door in [[PointerHandler]].
 */
object KeyboardHandler:

  type Effects = EventHandler.Effects

  // ── Device lifecycle ────────────────────────────────────────────────

  def added(id: KeyboardId)(using Frame): Unit < Effects =
    for
      s <- Var.get[CompositorState]
      newKeyboards  = if s.keyboards.contains(id) then s.keyboards else s.keyboards :+ id
      becomesActive = s.activeKeyboard.isEmpty
      _ <- Var.set(s.copy(keyboards = newKeyboards, activeKeyboard = s.activeKeyboard.orElse(Some(id))))
      _ <- if becomesActive then Emit.value(ShellEffect.SetActiveKeyboard(id))
           else ((): Unit < Emit[ShellEffect])
    yield ()

  def removed(id: KeyboardId)(using Frame): Unit < Effects =
    for
      s <- Var.get[CompositorState]
      newKeyboards = s.keyboards.filterNot(_ == id)
      newActive = s.activeKeyboard match
        case Some(aid) if aid == id => newKeyboards.headOption
        case other                  => other
      _ <- Var.set(s.copy(keyboards = newKeyboards, activeKeyboard = newActive))
    yield ()

  // ── Key events ──────────────────────────────────────────────────────

  def keyPress(e: InputEvent.KeyPress)(using Frame): Unit < Effects =
    for
      _ <- markActive(e.keyboardId)
      s <- Var.get[CompositorState]
      _ <- s.launcherText match
        case Some(_) =>
          // Launcher is open — the shell routes keys into LauncherHandler
          // imperatively (keysym → LauncherInput conversion needs XKB/FFI).
          // Do not emit ForwardKeyToClient so the key is swallowed here.
          ((): Unit < Effects)
        case None =>
          for
            h1 <- KeyActionHandler.handleKey(KeyEvent(e.keysym, e.modifiers, Pressed))
            h2 <- if h1 then (true: Boolean < Effects)
                  else KeyActionHandler.handleKey(KeyEvent(e.rawKeysym, e.modifiers, Pressed))
            _  <- if h2 then ((): Unit < Effects)
                  else Emit.value(
                    ShellEffect.ForwardKeyToClient(e.keyboardId, e.time, e.keycode, true)
                  )
          yield ()
    yield ()

  def keyRelease(e: InputEvent.KeyRelease)(using Frame): Unit < Effects =
    Emit.value(ShellEffect.ForwardKeyToClient(e.keyboardId, e.time, e.keycode, false))

  private def markActive(id: KeyboardId)(using Frame): Unit < Effects =
    for
      s <- Var.get[CompositorState]
      _ <- Var.set(s.copy(activeKeyboard = Some(id)))
      _ <- Emit.value(ShellEffect.SetActiveKeyboard(id))
    yield ()
