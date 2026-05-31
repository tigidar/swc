package core.ipc

/**
 * The set of commands that can be sent to the compositor over the IPC socket.
 *
 * Each variant maps to a JSON object with a `"cmd"` discriminator field
 * using kebab-case names (e.g. `ListWindows` → `"list-windows"`).
 *
 * Codecs are derived by jsoniter-scala in [[IpcCodec]].
 */
sealed trait IpcCommand

// Window management
case object ListWindows extends IpcCommand
case object GetFocused extends IpcCommand
case object CloseFocused extends IpcCommand
case object Exit extends IpcCommand
case class Spawn(args: List[String]) extends IpcCommand

// Layout control
case class LayoutSetMasterRatio(value: Double) extends IpcCommand
case class LayoutSetMasterCount(value: Int) extends IpcCommand

// Output management
case object ListOutputs extends IpcCommand
case object GetFocusedOutput extends IpcCommand
case class FocusOutputCmd(index: Int) extends IpcCommand
case class MoveToOutputCmd(index: Int) extends IpcCommand

// Workspace management
case class SwitchWorkspaceCmd(workspace: Int) extends IpcCommand
case class MoveToWorkspaceCmd(workspace: Int) extends IpcCommand

// Gamma control
case class GammaSet(value: Float) extends IpcCommand
case object GammaResetCmd extends IpcCommand

// Status bar
case object GetStatus extends IpcCommand
