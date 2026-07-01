package core.state

import kyo.*
import core.windows.{WindowId, WindowState}
import core.geometry.Rect
import core.output.{OutputId, WorkspaceId}

/**
 * Pure window-lifecycle handlers.
 *
 * Allocate, map, unmap, and destroy windows inside the per-output
 * per-workspace structure, emitting the [[ShellEffect]]s the compositor
 * shell needs to mirror the change in wlroots (retile, focus, show/hide).
 */
object WindowHandler:

  /** Allocate the next WindowId. Does not assign to any output yet. */
  def createWindow(
    title: Option[String],
    appId: Option[String]
  )(using Frame): WindowId < Var[CompositorState] =
    for
      s  <- Var.get[CompositorState]
      id  = WindowId(s.nextId)
      _  <- Var.set(s.copy(nextId = s.nextId + 1))
    yield id

  /** Map a window: apply window rules, add to target output/workspace, focus, retile. */
  def mapWindow(id: WindowId, outputId: OutputId, title: Option[String], appId: Option[String])(using Frame): Unit < EventHandler.Effects =
    for
      cfg <- Env.get[CompositorConfig]
      s   <- Var.get[CompositorState]
      // Apply window rules
      rule = appId.flatMap(aid => cfg.windowRules.find(_.matches(aid)))
      targetOutput = rule.flatMap(_.output).map(OutputId(_))
        .filter(s.outputs.contains).getOrElse(outputId)
      targetWsId = rule.flatMap(_.workspace).map(WorkspaceId(_))
      isFloating = rule.flatMap(_.floating).getOrElse(false)
      ws = WindowState(id, mapped = true, title = title, appId = appId,
        geometry = Rect(0, 0, 0, 0), floating = isFloating)
      // Determine which workspace to place on
      actualWsId = targetWsId.getOrElse(s.outputs.get(targetOutput).map(_.activeWorkspace).getOrElse(WorkspaceId(1)))
      _ <- Var.set(s.updateOutput(targetOutput) { out =>
        val targetWs = out.workspaces(actualWsId)
        val wl = targetWs.windows.add(ws).setMapped(id, true)
        val fm = targetWs.focus.focus(id, wl)
        out.copy(workspaces = out.workspaces.updated(actualWsId, targetWs.copy(windows = wl, focus = fm)))
      })
      s2 <- Var.get[CompositorState]
      activeWs = s2.outputs.get(targetOutput).map(_.activeWorkspace)
      onActiveWorkspace = activeWs.contains(actualWsId)
      _ <- Emit.value(ShellEffect.RetileOutput(targetOutput))
      _ <- if !onActiveWorkspace then Emit.value(ShellEffect.HideWindow(id))
           else ((): Unit < EventHandler.Effects)
      _ <- if isFloating && onActiveWorkspace then Emit.value(ShellEffect.RaiseWindow(id))
           else ((): Unit < EventHandler.Effects)
      _ <- s2.outputs.get(targetOutput).flatMap(o => o.workspaces.get(actualWsId)).flatMap(_.focus.focused) match
        case Some(fid) if onActiveWorkspace => Emit.value(ShellEffect.SetKeyboardFocus(fid))
        case _ => Emit.value(ShellEffect.ClearKeyboardFocus)
    yield ()

  /** Unmap a window: clear mapped flag, transfer focus, retile. */
  def unmapWindow(id: WindowId)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      loc = s.findWindow(id)
      _ <- loc match
        case Some((oid, wsId)) =>
          for
            _ <- Var.set(s.updateOutput(oid) { out =>
              val ws = out.workspaces(wsId)
              val wl = ws.windows.setMapped(id, false)
              val fm = ws.focus.unfocus(id, wl)
              out.copy(workspaces = out.workspaces.updated(wsId, ws.copy(windows = wl, focus = fm)))
            })
            s2 <- Var.get[CompositorState]
            _ <- Emit.value(ShellEffect.RetileOutput(oid))
            _ <- s2.outputs.get(oid).flatMap(o => o.workspaces.get(wsId)).flatMap(_.focus.focused) match
              case Some(fid) => Emit.value(ShellEffect.SetKeyboardFocus(fid))
              case None => Emit.value(ShellEffect.ClearKeyboardFocus)
          yield ()
        case None => Emit.value(ShellEffect.ClearKeyboardFocus)
    yield ()

  /** Remove a window entirely, cancel any grab on it, and transfer focus. */
  def destroyWindow(id: WindowId)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      newGrab = s.grabState.flatMap {
        case g if g.windowId == id => None
        case g                     => Some(g)
      }
      loc = s.findWindow(id)
      _ <- loc match
        case Some((oid, wsId)) =>
          for
            _ <- Var.set(s.copy(grabState = newGrab).updateOutput(oid) { out =>
              val ws = out.workspaces(wsId)
              out.copy(workspaces = out.workspaces.updated(wsId, ws.copy(windows = ws.windows.remove(id))))
            })
            _ <- Emit.value(ShellEffect.RetileOutput(oid))
            s2 <- Var.get[CompositorState]
            _ <- s2.outputs.get(oid).flatMap(o => o.workspaces.get(wsId)).flatMap(_.focus.focused) match
              case Some(fid) => Emit.value(ShellEffect.SetKeyboardFocus(fid))
              case None => Emit.value(ShellEffect.ClearKeyboardFocus)
          yield ()
        case None =>
          Var.set(s.copy(grabState = newGrab)).unit
    yield ()
