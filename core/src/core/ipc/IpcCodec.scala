package core.ipc

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * Type-safe JSON codec for the IPC protocol, powered by jsoniter-scala.
 *
 * The wire format uses kebab-case discriminator fields:
 *
 * Commands (discriminator: "cmd"):
 *   {"cmd":"list-windows"}
 *   {"cmd":"switch-workspace","workspace":3}
 *   {"cmd":"focus-output","index":1}
 *
 * Responses (discriminator: "type"):
 *   {"type":"ok"}
 *   {"type":"outputs-listed","outputs":[...]}
 */
object IpcCodec:

  private given JsonValueCodec[IpcCommand] = JsonCodecMaker.make(
    CodecMakerConfig
      .withDiscriminatorFieldName(Some("cmd"))
      .withAdtLeafClassNameMapper {
        case "core.ipc.ListWindows"           => "list-windows"
        case "core.ipc.GetFocused"            => "get-focused"
        case "core.ipc.CloseFocused"          => "close-focused"
        case "core.ipc.Exit"                  => "exit"
        case "core.ipc.Spawn"                 => "spawn"
        case "core.ipc.LayoutSetMasterRatio"  => "layout-set-master-ratio"
        case "core.ipc.LayoutSetMasterCount"  => "layout-set-master-count"
        case "core.ipc.ListOutputs"           => "list-outputs"
        case "core.ipc.GetFocusedOutput"      => "get-focused-output"
        case "core.ipc.FocusOutputCmd"        => "focus-output"
        case "core.ipc.MoveToOutputCmd"       => "move-to-output"
        case "core.ipc.SwitchWorkspaceCmd"    => "switch-workspace"
        case "core.ipc.MoveToWorkspaceCmd"    => "move-to-workspace"
        case "core.ipc.GammaSet"              => "gamma-set"
        case "core.ipc.GammaResetCmd"         => "gamma-reset"
        case "core.ipc.GetStatus"             => "get-status"
      }
  )

  private given JsonValueCodec[IpcResponse] = JsonCodecMaker.make(
    CodecMakerConfig
      .withDiscriminatorFieldName(Some("type"))
      .withTransientEmpty(false)
      .withAdtLeafClassNameMapper {
        case "core.ipc.WindowsListed"         => "windows-listed"
        case "core.ipc.FocusedWindow"         => "focused-window"
        case "core.ipc.OutputsListed"         => "outputs-listed"
        case "core.ipc.FocusedOutputResponse" => "focused-output"
        case "core.ipc.StatusResponse"        => "status"
        case "core.ipc.Ok"                    => "ok"
        case "core.ipc.Err"                   => "err"
      }
  )

  def parse(line: String): Either[String, IpcCommand] =
    try Right(readFromString[IpcCommand](line.trim))
    catch case e: JsonReaderException => Left(e.getMessage)

  def encode(response: IpcResponse): String =
    writeToString(response)

  def encodeCommand(cmd: IpcCommand): String =
    writeToString(cmd)

  def parseResponse(line: String): Either[String, IpcResponse] =
    try Right(readFromString[IpcResponse](line.trim))
    catch case e: JsonReaderException => Left(e.getMessage)
