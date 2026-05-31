package core.output

import core.windows.{WindowList, FocusModel}
import core.layout.MasterStackConfig

/**
 * Per-workspace state. Each output has 9 workspaces, each with its own
 * window list, focus model, and tiling configuration.
 *
 * Switching workspaces on an output means switching which Workspace is
 * "active" — the shell hides windows from the old workspace and shows
 * windows from the new one.
 */
case class Workspace(
  windows:      WindowList,
  focus:        FocusModel,
  tilingConfig: MasterStackConfig
)

object Workspace:
  val empty: Workspace = Workspace(
    windows      = WindowList.empty,
    focus        = FocusModel.empty,
    tilingConfig = MasterStackConfig.default
  )

  /** Create the default set of 9 empty workspaces. */
  def defaultWorkspaces: Map[WorkspaceId, Workspace] =
    WorkspaceId.all.map(id => id -> empty).toMap
