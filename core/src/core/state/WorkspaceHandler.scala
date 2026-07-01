package core.state

import kyo.*
import core.windows.WindowId
import core.output.WorkspaceId

/**
 * Pure workspace-navigation handlers.
 *
 * Switch the focused output between its 9 workspaces and move the focused
 * window to another workspace (same output) or to another output's active
 * workspace, emitting the show/hide/retile/focus [[ShellEffect]]s the shell
 * needs to mirror the change.
 */
object WorkspaceHandler:

  /** Switch the focused output to a different workspace. */
  def switchWorkspace(wsId: WorkspaceId)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- s.focusedOutput match
        case None => Emit.value(ShellEffect.ClearKeyboardFocus)
        case Some(oid) =>
          s.outputs.get(oid) match
            case None => Emit.value(ShellEffect.ClearKeyboardFocus)
            case Some(out) if out.activeWorkspace == wsId =>
              Emit.value(ShellEffect.ClearKeyboardFocus).andThen(()) // already on this workspace
            case Some(out) =>
              val oldWs = out.active
              val newWs = out.workspaces(wsId)
              for
                // Hide old workspace windows
                _ <- emitForAll(oldWs.windows.ordered.filter(id => oldWs.windows.get(id).exists(_.mapped)), ShellEffect.HideWindow(_))
                // Show new workspace windows
                _ <- emitForAll(newWs.windows.ordered.filter(id => newWs.windows.get(id).exists(_.mapped)), ShellEffect.ShowWindow(_))
                // Update active workspace
                _ <- Var.set(s.updateOutput(oid)(_.copy(activeWorkspace = wsId)))
                // Retile and focus
                _ <- Emit.value(ShellEffect.RetileOutput(oid))
                _ <- EventHandler.emitFocusChange(newWs.focus)
              yield ()
    yield ()

  /** Move the focused window to a workspace on the same output. */
  def moveWindowToWorkspace(wsId: WorkspaceId)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- s.focusedOutput match
        case None => Emit.value(ShellEffect.ClearKeyboardFocus)
        case Some(oid) =>
          val out = s.outputs(oid)
          val currentWs = out.active
          currentWs.focus.focused match
            case None => Emit.value(ShellEffect.ClearKeyboardFocus)
            case Some(wid) =>
              val winState = currentWs.windows.get(wid)
              winState match
                case None => Emit.value(ShellEffect.ClearKeyboardFocus)
                case Some(ws) =>
                  val newCurrentWindows = currentWs.windows.remove(wid)
                  val updatedCurrent = currentWs.copy(
                    windows = newCurrentWindows,
                    focus   = currentWs.focus.unfocus(wid, newCurrentWindows)
                  )
                  val targetWs = out.workspaces(wsId)
                  val newTargetWindows = targetWs.windows.add(ws)
                  val updatedTarget = targetWs.copy(
                    windows = newTargetWindows,
                    focus   = targetWs.focus.focus(wid, newTargetWindows)
                  )
                  for
                    _ <- Var.set(s.updateOutput(oid)(o => o.copy(
                      workspaces = o.workspaces
                        .updated(o.activeWorkspace, updatedCurrent)
                        .updated(wsId, updatedTarget)
                    )))
                    _ <- Emit.value(ShellEffect.HideWindow(wid))
                    _ <- Emit.value(ShellEffect.RetileOutput(oid))
                    _ <- EventHandler.emitFocusChange(updatedCurrent.focus)
                  yield ()
    yield ()

  /** Move the focused window to a different output's active workspace. */
  def moveWindowToOutputByIndex(index: Int)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      ordered = s.outputOrder
      _ <- s.focusedOutput match
        case None => Emit.value(ShellEffect.ClearKeyboardFocus)
        case Some(srcOid) if index < 0 || index >= ordered.size =>
          Emit.value(ShellEffect.ClearKeyboardFocus)
        case Some(srcOid) =>
          val dstOid = ordered(index)
          if srcOid == dstOid then Emit.value(ShellEffect.ClearKeyboardFocus) // same output, no-op
          else
            val srcOut = s.outputs(srcOid)
            val srcWs = srcOut.active
            srcWs.focus.focused match
              case None => Emit.value(ShellEffect.ClearKeyboardFocus)
              case Some(wid) =>
                srcWs.windows.get(wid) match
                  case None => Emit.value(ShellEffect.ClearKeyboardFocus)
                  case Some(winState) =>
                    val dstOut = s.outputs(dstOid)
                    val dstWs = dstOut.active
                    val newSrcWindows = srcWs.windows.remove(wid)
                    val newSrcWs = srcWs.copy(
                      windows = newSrcWindows,
                      focus   = srcWs.focus.unfocus(wid, newSrcWindows)
                    )
                    val newDstWindows = dstWs.windows.add(winState)
                    val newDstWs = dstWs.copy(
                      windows = newDstWindows,
                      focus   = dstWs.focus.focus(wid, newDstWindows)
                    )
                    for
                      _ <- Var.set(s
                        .updateOutput(srcOid)(o => o.updateActive(_ => newSrcWs))
                        .updateOutput(dstOid)(o => o.updateActive(_ => newDstWs))
                      )
                      _ <- Emit.value(ShellEffect.RetileOutput(srcOid))
                      _ <- Emit.value(ShellEffect.RetileOutput(dstOid))
                      _ <- EventHandler.emitFocusChange(newSrcWs.focus)
                    yield ()
    yield ()

  /** Emit a ShellEffect for each WindowId in the list. No-op for empty list. */
  private def emitForAll(ids: List[WindowId], f: WindowId => ShellEffect)(using Frame): Unit < Emit[ShellEffect] =
    ids match
      case Nil => ((): Unit < Emit[ShellEffect])
      case head :: Nil => Emit.value(f(head))
      case head :: tail => Emit.value(f(head)).andThen(emitForAll(tail, f))
