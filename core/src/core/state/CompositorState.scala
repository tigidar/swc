package core.state

import core.input.KeyboardId
import core.output.{OutputId, OutputInfo, WorkspaceId, Workspace}
import core.windows.{WindowId, WindowList, FocusModel}
import core.layout.MasterStackConfig

/**
 * Pure, immutable snapshot of the compositor's logical state.
 *
 * Windows live inside per-output per-workspace structures. The global
 * state tracks outputs, which output is focused, the next window ID
 * to allocate, and the active grab.
 *
 * FFI handles (Ptr[WlDisplay], Ptr[WlrBackend], ...) and pointer maps
 * remain in the compositor shell — they are not part of the pure model.
 */
case class CompositorState(
  outputs:       Map[OutputId, OutputInfo],
  outputOrder:   List[OutputId],
  focusedOutput: Option[OutputId],
  nextId:        Long,
  grabState:     Option[GrabState],
  gammaFactor:   Float = 1.0f,
  launcherText:  Option[String] = None,  // None = closed, Some("") = open with empty input
  keyboards:       List[KeyboardId]   = Nil,
  activeKeyboard:  Option[KeyboardId] = None,
  cursorX:         Double             = 0.0,
  cursorY:         Double             = 0.0,
  pointerFocus:    Option[WindowId]   = None,
  activatedWindow: Option[WindowId]   = None
):

  /** The focused output's info, if any. */
  def focused: Option[OutputInfo] =
    focusedOutput.flatMap(outputs.get)

  /** The active workspace on the focused output, if any. */
  def activeWorkspace: Option[Workspace] =
    focused.map(_.active)

  /** The window list from the active workspace on the focused output. */
  def activeWindowList: WindowList =
    activeWorkspace.map(_.windows).getOrElse(WindowList.empty)

  /** The focus model from the active workspace on the focused output. */
  def activeFocusModel: FocusModel =
    activeWorkspace.map(_.focus).getOrElse(FocusModel.empty)

  /** The tiling config from the active workspace on the focused output. */
  def activeTilingConfig: MasterStackConfig =
    activeWorkspace.map(_.tilingConfig).getOrElse(MasterStackConfig.default)

  /** Update the focused output's active workspace. No-op if no focused output. */
  def updateFocusedWorkspace(f: Workspace => Workspace): CompositorState =
    focusedOutput match
      case None => this
      case Some(oid) =>
        outputs.get(oid) match
          case None => this
          case Some(out) =>
            copy(outputs = outputs.updated(oid, out.updateActive(f)))

  /** Update a specific output. No-op if output doesn't exist. */
  def updateOutput(oid: OutputId)(f: OutputInfo => OutputInfo): CompositorState =
    outputs.get(oid) match
      case None => this
      case Some(out) => copy(outputs = outputs.updated(oid, f(out)))

  /** Find which output and workspace a window belongs to. */
  def findWindow(id: WindowId): Option[(OutputId, WorkspaceId)] =
    outputs.collectFirst {
      case (oid, out) =>
        out.workspaces.collectFirst {
          case (wsId, ws) if ws.windows.get(id).isDefined => (oid, wsId)
        }
    }.flatten

object CompositorState:
  val empty: CompositorState = CompositorState(
    outputs       = Map.empty,
    outputOrder   = Nil,
    focusedOutput = None,
    nextId        = 0L,
    grabState     = None
  )
