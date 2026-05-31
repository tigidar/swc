package core.output

import core.geometry.Rect
import core.windows.{WindowId, WindowList, FocusModel}
import core.layout.MasterStackConfig

/**
 * Pure model of a connected output (monitor).
 *
 * Tracks dimensions, layout position, usable area (after layer-shell
 * exclusive zones), and per-workspace state. Each output has 9 workspaces;
 * one is active (visible) at a time.
 */
case class OutputInfo(
  id:              OutputId,
  width:           Int,
  height:          Int,
  layoutX:         Int,
  layoutY:         Int,
  usableArea:      Rect,
  activeWorkspace: WorkspaceId,
  workspaces:      Map[WorkspaceId, Workspace]
):

  /** The currently active workspace on this output. */
  def active: Workspace = workspaces(activeWorkspace)

  /** Update the active workspace via a transformation function. */
  def updateActive(f: Workspace => Workspace): OutputInfo =
    copy(workspaces = workspaces.updated(activeWorkspace, f(active)))

  /** All window IDs across all workspaces on this output. */
  def allWindows: List[WindowId] =
    workspaces.values.flatMap(_.windows.ordered).toList

  /** The center point of this output's usable area. */
  def center: (Int, Int) =
    (usableArea.x + usableArea.w / 2, usableArea.y + usableArea.h / 2)

object OutputInfo:

  /** Create a new output with 9 empty workspaces, workspace 1 active. */
  def create(
    id: OutputId,
    width: Int, height: Int,
    layoutX: Int, layoutY: Int
  ): OutputInfo =
    OutputInfo(
      id              = id,
      width           = width,
      height          = height,
      layoutX         = layoutX,
      layoutY         = layoutY,
      usableArea      = Rect(layoutX, layoutY, width, height),
      activeWorkspace = WorkspaceId(1),
      workspaces      = Workspace.defaultWorkspaces
    )
