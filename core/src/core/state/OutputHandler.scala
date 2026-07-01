package core.state

import kyo.*
import core.geometry.Rect
import core.output.{OutputId, OutputInfo}

/**
 * Pure output-lifecycle and output-focus handlers.
 *
 * Register/remove outputs (each with 9 empty workspaces), keep their
 * geometry and usable area in sync with the layer-shell arrangement, and
 * move keyboard focus between outputs (warping the cursor to follow).
 */
object OutputHandler:

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
                _ <- EventHandler.emitFocusChange(out.active.focus)
              yield ()
            case None => Emit.value(ShellEffect.ClearKeyboardFocus)
        yield ()
      else
        Emit.value(ShellEffect.ClearKeyboardFocus)
    yield ()
