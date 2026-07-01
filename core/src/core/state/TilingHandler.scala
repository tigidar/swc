package core.state

import kyo.*

/**
 * Pure master/stack tiling-config handlers for the focused output's active
 * workspace. `set*` apply an absolute value (used by IPC); `adjust*` apply a
 * relative delta and report whether they handled the key (used by keybinding
 * dispatch). Both clamp to valid ranges and emit a retile effect.
 */
object TilingHandler:

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

  /** Adjust master ratio by a relative delta (clamped). Returns true (handled). */
  def adjustMasterRatio(delta: Double)(using Frame): Boolean < (Var[CompositorState] & Emit[ShellEffect]) =
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

  /** Adjust master count by a relative delta (clamped to >= 1). Returns true (handled). */
  def adjustMasterCount(delta: Int)(using Frame): Boolean < (Var[CompositorState] & Emit[ShellEffect]) =
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
