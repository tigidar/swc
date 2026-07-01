package core.state

import kyo.*
import core.windows.WindowId

/**
 * Pure actions on the focused window of the focused output's active
 * workspace: toggle floating / fullscreen, cycle focus to the next mapped
 * window, and request focus on a specific window. Each emits the retile /
 * focus / raise [[ShellEffect]]s the shell applies to wlroots.
 */
object WindowActions:

  /** Toggle the focused window between tiled and floating. */
  def toggleFloating(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- s.focusedOutput match
        case None => Emit.value(ShellEffect.ClearKeyboardFocus)
        case Some(oid) =>
          val out = s.outputs(oid)
          val ws = out.active
          ws.focus.focused match
            case None => Emit.value(ShellEffect.ClearKeyboardFocus)
            case Some(wid) =>
              ws.windows.get(wid) match
                case None => Emit.value(ShellEffect.ClearKeyboardFocus)
                case Some(win) =>
                  val newFloating = !win.floating
                  val updated = win.copy(floating = newFloating, fullscreen = false)
                  for
                    _ <- Var.set(s.updateOutput(oid)(_.updateActive(w =>
                      w.copy(windows = w.windows.add(updated)))))
                    _ <- Emit.value(ShellEffect.RetileOutput(oid))
                    _ <- if newFloating then Emit.value(ShellEffect.RaiseWindow(wid))
                         else ((): Unit < Emit[ShellEffect])
                  yield ()
    yield ()

  /** Toggle the focused window between normal and fullscreen. */
  def toggleFullscreen(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- s.focusedOutput match
        case None => Emit.value(ShellEffect.ClearKeyboardFocus)
        case Some(oid) =>
          val out = s.outputs(oid)
          val ws = out.active
          ws.focus.focused match
            case None => Emit.value(ShellEffect.ClearKeyboardFocus)
            case Some(wid) =>
              ws.windows.get(wid) match
                case None => Emit.value(ShellEffect.ClearKeyboardFocus)
                case Some(win) =>
                  val newFs = !win.fullscreen
                  val updated = win.copy(fullscreen = newFs, floating = false)
                  val fsEffect =
                    if newFs then ShellEffect.SetFullscreen(wid, out.layoutX, out.layoutY, out.width, out.height)
                    else ShellEffect.UnsetFullscreen(wid)
                  for
                    _ <- Var.set(s.updateOutput(oid)(_.updateActive(w =>
                      w.copy(windows = w.windows.add(updated)))))
                    _ <- Emit.value(fsEffect)
                    _ <- Emit.value(ShellEffect.RetileOutput(oid))
                  yield ()
    yield ()

  /** Cycle focus to the next mapped window in the active workspace. */
  def cycleFocus(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- s.focusedOutput match
        case None => Emit.value(ShellEffect.ClearKeyboardFocus)
        case Some(oid) =>
          val out = s.outputs(oid)
          val ws = out.active
          val mapped = ws.windows.ordered.filter(id => ws.windows.get(id).exists(_.mapped))
          mapped match
            case Nil => Emit.value(ShellEffect.ClearKeyboardFocus)
            case _ :: Nil => Emit.value(ShellEffect.ClearKeyboardFocus) // single window, no-op
            case _ =>
              val currentIdx = ws.focus.focused.map(mapped.indexOf).getOrElse(-1)
              val nextIdx = if currentIdx < 0 then 0 else (currentIdx + 1) % mapped.size
              requestFocus(mapped(nextIdx))
    yield ()

  /** Request focus on a specific window. */
  def requestFocus(id: WindowId)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      loc = s.findWindow(id)
      _ <- loc match
        case Some((oid, wsId)) =>
          for
            _ <- Var.set(s.updateOutput(oid) { out =>
              val ws = out.workspaces(wsId)
              val fm = ws.focus.focus(id, ws.windows)
              out.copy(workspaces = out.workspaces.updated(wsId, ws.copy(focus = fm)))
            })
            _ <- Emit.value(ShellEffect.SetKeyboardFocus(id))
          yield ()
        case None => Emit.value(ShellEffect.ClearKeyboardFocus)
    yield ()
