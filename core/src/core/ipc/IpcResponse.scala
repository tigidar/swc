package core.ipc

/**
 * The set of responses the compositor sends back over the IPC socket.
 *
 * Each variant maps to a JSON object with a `"type"` discriminator field
 * using kebab-case names (e.g. `WindowsListed` → `"windows-listed"`).
 *
 * Codecs are derived by jsoniter-scala in [[IpcCodec]].
 */
sealed trait IpcResponse

// Window responses
case class WindowsListed(windows: List[WindowInfo]) extends IpcResponse
case class FocusedWindow(id: Option[Long]) extends IpcResponse

// Output responses
case class OutputsListed(outputs: List[OutputInfoResponse]) extends IpcResponse
case class FocusedOutputResponse(name: Option[String]) extends IpcResponse

// Status bar
case class StatusResponse(
  outputs: List[OutputStatusInfo]
) extends IpcResponse

case class OutputStatusInfo(
  name: String,
  focused: Boolean,
  activeWorkspace: Int,
  occupiedWorkspaces: List[Int],
  focusedWindowTitle: Option[String]
)

// Generic
case object Ok extends IpcResponse
case class Err(message: String) extends IpcResponse

// Data transfer objects
case class WindowInfo(id: Long, title: String, appId: String, mapped: Boolean)
case class OutputInfoResponse(
  name: String,
  width: Int,
  height: Int,
  layoutX: Int,
  layoutY: Int,
  activeWorkspace: Int,
  focused: Boolean
)
