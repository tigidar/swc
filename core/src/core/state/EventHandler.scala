package core.state

import kyo.*
import core.windows.{WindowId, WindowState, WindowList, FocusModel}
import core.layout.MasterStackConfig
import core.input.*
import core.geometry.Rect
import core.output.{OutputId, OutputInfo, WorkspaceId, Workspace}

/**
 * Pure event handler built on Kyo effects.
 *
 * Operations target the focused output's active workspace unless
 * an explicit OutputId is provided. The compositor shell calls [[run]]
 * to interpret a pipeline, getting back the new state, collected
 * effects, and result value.
 */
object EventHandler:

  /** The full effect set for pipelines. */
  type Effects = Env[CompositorConfig] & Var[CompositorState] & Emit[ShellEffect]

  // ── Runner ──────────────────────────────────────────────────────────

  def run[A](config: CompositorConfig, state: CompositorState)(
    pipeline: A < Effects
  )(using Frame): (CompositorState, Chunk[ShellEffect], A) =
    val handled: (CompositorState, (Chunk[ShellEffect], A)) < Any =
      Var.runTuple(state) {
        Emit.run[ShellEffect] {
          Env.run(config)(pipeline)
        }
      }
    val (newState, (effects, result)) = handled.eval
    (newState, effects, result)

  def runAbort[E: ConcreteTag, A](config: CompositorConfig, state: CompositorState)(
    pipeline: A < (Effects & Abort[E])
  )(using Frame): (CompositorState, Chunk[ShellEffect], Result[E, A]) =
    val handled: (CompositorState, (Chunk[ShellEffect], Result[E, A])) < Any =
      Var.runTuple(state) {
        Emit.run[ShellEffect] {
          Abort.run[E] {
            Env.run(config)(pipeline)
          }
        }
      }
    val (newState, (effects, result)) = handled.eval
    (newState, effects, result)

  // ── Output management ───────────────────────────────────────────────

  /** Register a new output with 9 empty workspaces. Auto-focuses if first output. */
  def addOutput(id: OutputId, width: Int, height: Int, layoutX: Int, layoutY: Int)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      out = OutputInfo.create(id, width, height, layoutX, layoutY)
      newFocused = if s.focusedOutput.isEmpty then Some(id) else s.focusedOutput
      _ <- Var.set(s.copy(
        outputs = s.outputs + (id -> out),
        outputOrder = s.outputOrder :+ id,
        focusedOutput = newFocused
      ))
    yield ()

  /** Remove an output. Reassigns its windows to the first remaining output. */
  def removeOutput(id: OutputId)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      removed = s.outputs.get(id)
      remaining = s.outputs - id
      newOrder = s.outputOrder.filterNot(_ == id)
      // Reassign all windows from removed output to first remaining output's active workspace
      newOutputs = removed match
        case None => remaining
        case Some(out) =>
          val windowIds = out.allWindows
          if windowIds.isEmpty || remaining.isEmpty then remaining
          else
            val targetId = newOrder.headOption.getOrElse(remaining.keys.head)
            val target = remaining(targetId)
            val updatedTarget = windowIds.foldLeft(target) { (t, wid) =>
              out.workspaces.values.flatMap(ws => ws.windows.get(wid)).headOption match
                case Some(ws) => t.updateActive(w => w.copy(windows = w.windows.add(ws)))
                case None => t
            }
            remaining.updated(targetId, updatedTarget)
      newFocused = if s.focusedOutput.contains(id) then newOrder.headOption else s.focusedOutput
      _ <- Var.set(s.copy(outputs = newOutputs, outputOrder = newOrder, focusedOutput = newFocused))
      _ <- Emit.value(ShellEffect.Retile)
    yield ()

  /** Update an output's usable area (after layer-shell arrangement). */
  def updateUsableArea(id: OutputId, usableArea: Rect)(using Frame): Unit < Var[CompositorState] =
    Var.update[CompositorState](s =>
      s.updateOutput(id)(out => out.copy(usableArea = usableArea))
    ).unit

  /** Update an output's geometry (dimensions and layout position). */
  def updateOutputGeometry(id: OutputId, w: Int, h: Int, lx: Int, ly: Int)(using Frame): Unit < Var[CompositorState] =
    Var.update[CompositorState](s =>
      s.updateOutput(id)(out => out.copy(width = w, height = h, layoutX = lx, layoutY = ly))
    ).unit

  // ── Window lifecycle ────────────────────────────────────────────────

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
  def mapWindow(id: WindowId, outputId: OutputId, title: Option[String], appId: Option[String])(using Frame): Unit < Effects =
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
           else ((): Unit < Effects)
      _ <- if isFloating && onActiveWorkspace then Emit.value(ShellEffect.RaiseWindow(id))
           else ((): Unit < Effects)
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

  // ── Keybinding dispatch ─────────────────────────────────────────────

  def handleKey(event: KeyEvent)(using Frame): Boolean < Effects =
    for
      cfg <- Env.get[CompositorConfig]
      result <- cfg.keyBindings.dispatch(event) match
        case SpawnTerminal   => Emit.value(ShellEffect.SpawnProcess(List(cfg.terminalCmd))).andThen(true)
        case ExitCompositor  => Emit.value(ShellEffect.TerminateDisplay).andThen(true)
        case OpenLauncher    => LauncherHandler.openLauncher.andThen(true)
        case CloseFocused    => handleCloseFocused
        case CycleFocus      => cycleFocus.andThen(true)
        case ToggleFloating  => toggleFloating.andThen(true)
        case ToggleFullscreen => toggleFullscreen.andThen(true)
        case FocusOutput(i)  => focusOutputByIndex(i).andThen(true)
        case SwitchWorkspace(ws)  => switchWorkspace(WorkspaceId(ws)).andThen(true)
        case MoveToWorkspace(ws)  => moveWindowToWorkspace(WorkspaceId(ws)).andThen(true)
        case MoveToOutput(i)      => moveWindowToOutputByIndex(i).andThen(true)
        case IncreaseMasterRatio  => adjustMasterRatio(0.05)
        case DecreaseMasterRatio  => adjustMasterRatio(-0.05)
        case IncreaseMasterCount  => adjustMasterCount(1)
        case DecreaseMasterCount  => adjustMasterCount(-1)
        case action: MediaAction  => handleMediaKey(action)
        case action: GammaAction  => handleGammaKey(action)
        case ShowKeybindings      => handleShowKeybindings(cfg)
        case Passthrough          => false: Boolean < Effects
    yield result

  private def handleCloseFocused(using Frame): Boolean < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- s.activeFocusModel.focused match
        case Some(id) => Emit.value(ShellEffect.CloseWindow(id))
        case None     => Emit.value(ShellEffect.ClearKeyboardFocus)
    yield true

  private def handleMediaKey(action: MediaAction)(using Frame): Boolean < Emit[ShellEffect] =
    val effect = action match
      case BrightnessUp      => ShellEffect.AdjustBrightness(10)
      case BrightnessDown    => ShellEffect.AdjustBrightness(-10)
      case KbdBrightnessUp   => ShellEffect.AdjustKbdBrightness(1)
      case KbdBrightnessDown => ShellEffect.AdjustKbdBrightness(-1)
      case VolumeUp    => ShellEffect.SpawnProcess(List("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "5%+"))
      case VolumeDown  => ShellEffect.SpawnProcess(List("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "5%-"))
      case VolumeMute  => ShellEffect.SpawnProcess(List("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"))
      case MicMute     => ShellEffect.SpawnProcess(List("wpctl", "set-mute", "@DEFAULT_AUDIO_SOURCE@", "toggle"))
    Emit.value(effect).andThen(true)

  private def handleGammaKey(action: GammaAction)(using Frame): Boolean < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      newGamma = action match
        case GammaUp    => Math.min(1.0f, s.gammaFactor + 0.1f)
        case GammaDown  => Math.max(0.1f, s.gammaFactor - 0.1f)
        case GammaReset => 1.0f
      _ <- Var.set(s.copy(gammaFactor = newGamma))
      _ <- Emit.value(ShellEffect.SetGamma(newGamma))
    yield true

  private def handleShowKeybindings(cfg: CompositorConfig)(using Frame): Boolean < Emit[ShellEffect] =
    Emit.value(ShellEffect.SpawnProcess(List(
      cfg.terminalCmd, "-T", "SWC Keybindings", "-e", "sh", "-c",
      "echo 'SWC Keybindings'; echo '==============='; echo;" ++
      "echo 'GENERAL'; echo '  Super+Return       Open terminal'; echo '  Super+Space / R    Run launcher'; echo '  Super+Q            Close window'; echo '  Super+Escape       Exit compositor'; echo '  Super+S            This help'; echo;" ++
      "echo 'WINDOW MODE'; echo '  Super+J            Cycle focus'; echo '  Super+Shift+Space  Toggle floating'; echo '  Super+F / F11      Toggle fullscreen'; echo;" ++
      "echo 'TILING'; echo '  Super+H/L          Master ratio'; echo '  Super+Shift+H/L    Master count'; echo;" ++
      "echo 'WORKSPACES'; echo '  Super+1..9         Switch workspace'; echo '  Super+Shift+1..9   Move window'; echo;" ++
      "echo 'OUTPUTS'; echo '  Super+F1..F5       Focus output'; echo '  Super+Shift+F1..F5 Move to output'; echo;" ++
      "echo 'MEDIA KEYS (Fn)'; echo '  Brightness Up/Down  Display backlight'; echo '  Kbd Bright Up/Down  Keyboard backlight'; echo '  Volume Up/Down      Audio volume'; echo '  Mute                Toggle mute'; echo '  Mic Mute            Toggle mic mute'; echo;" ++
      "echo 'GAMMA'; echo '  Ctrl+Super+Up       Gamma +10%'; echo '  Ctrl+Super+Down     Gamma -10%'; echo '  Ctrl+Super+0        Reset gamma'; echo;" ++
      "echo 'RECORDING'; echo '  wf-recorder -f out.mp4'; echo '  wf-recorder --audio -f out.mp4'; echo;" ++
      "echo 'Press any key to close...'; read -n 1"
    ))).andThen(true)

  // ── Output focus ────────────────────────────────────────────────────

  /** Focus an output by index (0-based). Warps cursor to output center. */
  def focusOutputByIndex(index: Int)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      ordered = s.outputOrder
      _ <- if index >= 0 && index < ordered.size then
        val oid = ordered(index)
        for
          _ <- Var.set(s.copy(focusedOutput = Some(oid)))
          s2 <- Var.get[CompositorState]
          _ <- s2.outputs.get(oid) match
            case Some(out) =>
              val (cx, cy) = out.center
              for
                _ <- Emit.value(ShellEffect.WarpCursor(cx, cy))
                _ <- emitFocusChange(out.active.focus)
              yield ()
            case None => Emit.value(ShellEffect.ClearKeyboardFocus)
        yield ()
      else
        Emit.value(ShellEffect.ClearKeyboardFocus)
    yield ()

  // ── Workspace switching ─────────────────────────────────────────────

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
                _ <- emitFocusChange(newWs.focus)
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
                    _ <- emitFocusChange(updatedCurrent.focus)
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
                      _ <- emitFocusChange(newSrcWs.focus)
                    yield ()
    yield ()

  // ── Window mode toggles ────────────────────────────────────────────

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

  // ── Focus ───────────────────────────────────────────────────────────

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

  // ── Grab state ──────────────────────────────────────────────────────

  def beginGrab(grab: GrabState)(using Frame): Unit < Var[CompositorState] =
    Var.update[CompositorState](s => s.copy(grabState = Some(grab))).unit

  def releaseGrab(using Frame): Unit < Var[CompositorState] =
    Var.update[CompositorState](s => s.copy(grabState = None)).unit

  // ── Config setters (used by IPC) ──────────────────────────────────

  /** Set master ratio on the focused output's active workspace. */
  def setMasterRatio(ratio: Double)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- Var.set(s.updateFocusedWorkspace { ws =>
        val clamped = math.max(0.1, math.min(0.9, ratio))
        ws.copy(tilingConfig = ws.tilingConfig.copy(masterRatio = clamped))
      })
      _ <- s.focusedOutput match
        case Some(oid) => Emit.value(ShellEffect.RetileOutput(oid))
        case None => Emit.value(ShellEffect.Retile)
    yield ()

  /** Set master count on the focused output's active workspace. */
  def setMasterCount(count: Int)(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- Var.set(s.updateFocusedWorkspace { ws =>
        val clamped = math.max(1, count)
        ws.copy(tilingConfig = ws.tilingConfig.copy(masterCount = clamped))
      })
      _ <- s.focusedOutput match
        case Some(oid) => Emit.value(ShellEffect.RetileOutput(oid))
        case None => Emit.value(ShellEffect.Retile)
    yield ()

  // ── Internal helpers ────────────────────────────────────────────────

  private def emitFocusChange(fm: FocusModel)(using Frame): Unit < Emit[ShellEffect] =
    fm.focused match
      case Some(id) => Emit.value(ShellEffect.SetKeyboardFocus(id))
      case None     => Emit.value(ShellEffect.ClearKeyboardFocus)

  private def adjustMasterRatio(delta: Double)(using Frame): Boolean < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      currentRatio = s.activeTilingConfig.masterRatio
      newRatio = math.max(0.1, math.min(0.9, currentRatio + delta))
      _ <- Var.set(s.updateFocusedWorkspace(ws =>
        ws.copy(tilingConfig = ws.tilingConfig.copy(masterRatio = newRatio))
      ))
      _ <- s.focusedOutput match
        case Some(oid) => Emit.value(ShellEffect.RetileOutput(oid))
        case None => Emit.value(ShellEffect.Retile)
    yield true

  private def adjustMasterCount(delta: Int)(using Frame): Boolean < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      currentCount = s.activeTilingConfig.masterCount
      newCount = math.max(1, currentCount + delta)
      _ <- Var.set(s.updateFocusedWorkspace(ws =>
        ws.copy(tilingConfig = ws.tilingConfig.copy(masterCount = newCount))
      ))
      _ <- s.focusedOutput match
        case Some(oid) => Emit.value(ShellEffect.RetileOutput(oid))
        case None => Emit.value(ShellEffect.Retile)
    yield true

  /** Emit a ShellEffect for each WindowId in the list. No-op for empty list. */
  private def emitForAll(ids: List[WindowId], f: WindowId => ShellEffect)(using Frame): Unit < Emit[ShellEffect] =
    ids match
      case Nil => ((): Unit < Emit[ShellEffect])
      case head :: Nil => Emit.value(f(head))
      case head :: tail => Emit.value(f(head)).andThen(emitForAll(tail, f))
