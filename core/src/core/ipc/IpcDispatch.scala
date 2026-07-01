package core.ipc

import kyo.*
import core.windows.{WindowId, WindowList, FocusModel}
import core.state.{CompositorState, CompositorConfig, ShellEffect, EventHandler, OutputHandler, WorkspaceHandler, TilingHandler}
import core.output.{OutputId, WorkspaceId}

/**
 * Pure IPC dispatch using Kyo effects.
 *
 * Reads compositor state via [[Var]], emits [[ShellEffect]]s via [[Emit]],
 * and returns an [[IpcResponse]].
 */
object IpcDispatch:

  def dispatch(cmd: IpcCommand)(using Frame): IpcResponse < (Var[CompositorState] & Emit[ShellEffect]) =
    cmd match

      case ListWindows =>
        for
          s <- Var.get[CompositorState]
          infos = s.outputs.values.toList.flatMap { out =>
            out.workspaces.values.toList.flatMap { ws =>
              ws.windows.ordered.flatMap { id =>
                ws.windows.get(id).map { state =>
                  WindowInfo(
                    id     = WindowId.value(state.id),
                    title  = state.title.getOrElse(""),
                    appId  = state.appId.getOrElse(""),
                    mapped = state.mapped
                  )
                }
              }
            }
          }
        yield WindowsListed(infos)

      case GetFocused =>
        Var.get[CompositorState].map { s =>
          FocusedWindow(s.activeFocusModel.focused.map(WindowId.value))
        }

      case CloseFocused =>
        for
          s <- Var.get[CompositorState]
          _ <- s.activeFocusModel.focused match
                 case Some(id) => Emit.value(ShellEffect.CloseWindow(id))
                 case None     => Emit.value(ShellEffect.ClearKeyboardFocus)
        yield Ok

      case Exit =>
        Emit.value(ShellEffect.TerminateDisplay).andThen(Ok)

      case Spawn(args) =>
        Emit.value(ShellEffect.SpawnProcess(args)).andThen(Ok)

      case LayoutSetMasterRatio(ratio) =>
        TilingHandler.setMasterRatio(ratio).andThen(Ok)

      case LayoutSetMasterCount(count) =>
        TilingHandler.setMasterCount(count).andThen(Ok)

      // ── Output commands ──────────────────────────────────────────

      case ListOutputs =>
        for
          s <- Var.get[CompositorState]
          infos = s.outputOrder.flatMap { oid =>
            s.outputs.get(oid).map { out =>
              OutputInfoResponse(
                name            = OutputId.value(oid),
                width           = out.width,
                height          = out.height,
                layoutX         = out.layoutX,
                layoutY         = out.layoutY,
                activeWorkspace = WorkspaceId.value(out.activeWorkspace),
                focused         = s.focusedOutput.contains(oid)
              )
            }
          }
        yield OutputsListed(infos)

      case GetFocusedOutput =>
        Var.get[CompositorState].map { s =>
          FocusedOutputResponse(s.focusedOutput.map(OutputId.value))
        }

      case FocusOutputCmd(index) =>
        OutputHandler.focusOutputByIndex(index).andThen(Ok)

      case MoveToOutputCmd(index) =>
        WorkspaceHandler.moveWindowToOutputByIndex(index).andThen(Ok)

      // ── Workspace commands ───────────────────────────────────────

      case SwitchWorkspaceCmd(ws) =>
        WorkspaceHandler.switchWorkspace(WorkspaceId(ws)).andThen(Ok)

      case MoveToWorkspaceCmd(ws) =>
        WorkspaceHandler.moveWindowToWorkspace(WorkspaceId(ws)).andThen(Ok)

      // ── Gamma control ─────────────────────────────────────────────

      case GammaSet(value) =>
        val clamped = Math.max(0.1f, Math.min(1.0f, value))
        for
          s <- Var.get[CompositorState]
          _ <- Var.set(s.copy(gammaFactor = clamped))
          _ <- Emit.value(ShellEffect.SetGamma(clamped))
        yield Ok

      case GammaResetCmd =>
        for
          s <- Var.get[CompositorState]
          _ <- Var.set(s.copy(gammaFactor = 1.0f))
          _ <- Emit.value(ShellEffect.SetGamma(1.0f))
        yield Ok

      // ── Status bar ───────────────────────────────────────────────

      case GetStatus =>
        for
          s <- Var.get[CompositorState]
          infos = s.outputOrder.flatMap { oid =>
            s.outputs.get(oid).map { out =>
              val occupied = out.workspaces.collect {
                case (wsId, ws) if ws.windows.ordered.nonEmpty => WorkspaceId.value(wsId)
              }.toList.sorted
              val focusedTitle = out.active.focus.focused
                .flatMap(wid => out.active.windows.get(wid))
                .flatMap(_.title)
              OutputStatusInfo(
                name               = OutputId.value(oid),
                focused            = s.focusedOutput.contains(oid),
                activeWorkspace    = WorkspaceId.value(out.activeWorkspace),
                occupiedWorkspaces = occupied,
                focusedWindowTitle = focusedTitle
              )
            }
          }
        yield StatusResponse(infos)

      // ── Runtime configuration ─────────────────────────────────────

      case SetIdleTimeout(seconds) =>
        // Clamp to non-negative; 0 disables idle auto-exit. The shell owns the
        // CompositorConfig var and the wl idle timer, so we hand it the value
        // as a ShellEffect rather than mutating config from the pure pipeline.
        val ms = Math.max(0L, seconds) * 1000L
        Emit.value(ShellEffect.SetIdleTimeout(ms)).andThen(Ok)

      case SetScreenOffTimeout(seconds) =>
        // Clamp to non-negative; 0 disables screen-off. Same shell-owned-config
        // pattern as SetIdleTimeout: emit the effect, let the shell re-arm.
        val ms = Math.max(0L, seconds) * 1000L
        Emit.value(ShellEffect.SetScreenOffTimeout(ms)).andThen(Ok)
