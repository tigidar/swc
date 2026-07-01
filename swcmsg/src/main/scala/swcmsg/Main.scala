package swcmsg

import scala.scalanative.unsafe.*
import scala.scalanative.libc.{stdlib, stdio, string}
import scala.scalanative.unsigned.*
import scala.scalanative.posix.sys.socket.{socket, connect, AF_UNIX, SOCK_STREAM, sockaddr, socklen_t}
import scala.scalanative.posix.sys.un.sockaddr_un
import scala.scalanative.posix.sys.unOps.*
import scala.scalanative.posix.unistd.{read, write, close}

/**
 * swcmsg — IPC client for the SWC Wayland compositor.
 *
 * Connects to XDG_RUNTIME_DIR/swc.sock, sends one JSON command, prints the
 * response, and exits 0 on success or 1 on error.
 */
object Main:

  private final val BUF_SIZE = 4096

  def main(args: Array[String]): Unit =
    if args.isEmpty then
      printUsage()
      stdlib.exit(1)

    val json = buildJson(args)
    if json.isEmpty then
      printUsage()
      stdlib.exit(1)

    val z = Zone.open()
    given Zone = z

    val runtimeDirPtr = stdlib.getenv(c"XDG_RUNTIME_DIR")
    if runtimeDirPtr == null then
      stdio.fprintf(stdio.stderr, c"swcmsg: XDG_RUNTIME_DIR not set\n")
      stdlib.exit(1)

    val runtimeDir = fromCString(runtimeDirPtr)
    val socketPath = s"$runtimeDir/swc.sock"

    val fd = socket(AF_UNIX, SOCK_STREAM, 0)
    if fd < 0 then
      stdio.fprintf(stdio.stderr, c"swcmsg: socket() failed\n")
      stdlib.exit(1)

    // Build sockaddr_un using posixlib type-safe struct
    val addr = alloc[sockaddr_un]()
    string.memset(addr.asInstanceOf[Ptr[Byte]], 0, sizeof[sockaddr_un].toUSize)
    addr.sun_family = AF_UNIX.toUShort
    string.strncpy(addr.sun_path.at(0), toCString(socketPath), 107.toUSize)

    if connect(fd, addr.asInstanceOf[Ptr[sockaddr]], sizeof[sockaddr_un].toUInt) < 0 then
      stdio.fprintf(stdio.stderr, c"swcmsg: cannot connect to %s\n", toCString(socketPath))
      close(fd)
      stdlib.exit(1)

    // Send command + newline
    val line     = json + "\n"
    val lineCStr = toCString(line)
    val lineLen  = string.strlen(lineCStr)
    var sent: Long = 0L
    while sent < lineLen.toLong do
      val r = write(fd, lineCStr.asInstanceOf[Ptr[Byte]] + sent.toInt, (lineLen.toLong - sent).toUSize)
      if r <= 0 then
        stdio.fprintf(stdio.stderr, c"swcmsg: write error\n")
        close(fd)
        stdlib.exit(1)
      sent += r.toLong

    // Read response line
    val buf = stackalloc[Byte](BUF_SIZE.toUSize)
    string.memset(buf, 0, BUF_SIZE.toUSize)
    var totalRead = 0
    var reading   = true
    while reading && totalRead < BUF_SIZE - 1 do
      val r = read(fd, buf + totalRead, (BUF_SIZE - 1 - totalRead).toUSize)
      if r <= 0 then
        reading = false
      else
        var i = 0
        while i < r.toInt && reading do
          if !(buf + totalRead + i) == '\n'.toByte then
            !(buf + totalRead + i) = 0.toByte
            reading = false
          i += 1
        if reading then totalRead += r.toInt

    close(fd)

    val response = fromCString(buf)
    stdio.fprintf(stdio.stdout, c"%s\n", buf)

    z.close()

    if response.contains("\"type\":\"err\"") then
      stdlib.exit(1)

  private def buildJson(args: Array[String]): String =
    args(0) match
      case "list-windows"      => """{"cmd":"list-windows"}"""
      case "get-focused"       => """{"cmd":"get-focused"}"""
      case "close-focused"     => """{"cmd":"close-focused"}"""
      case "exit"              => """{"cmd":"exit"}"""
      case "get-status"        => """{"cmd":"get-status"}"""
      case "list-outputs"      => """{"cmd":"list-outputs"}"""
      case "get-focused-output" => """{"cmd":"get-focused-output"}"""
      case "spawn" =>
        if args.length < 2 then ""
        else
          val spawnArgs = args.drop(1).map(a => s""""$a"""").mkString("[", ",", "]")
          s"""{"cmd":"spawn","args":$spawnArgs}"""
      case "focus-output" =>
        if args.length < 2 then ""
        else s"""{"cmd":"focus-output","index":${args(1)}}"""
      case "move-to-output" =>
        if args.length < 2 then ""
        else s"""{"cmd":"move-to-output","index":${args(1)}}"""
      case "switch-workspace" =>
        if args.length < 2 then ""
        else s"""{"cmd":"switch-workspace","workspace":${args(1)}}"""
      case "move-to-workspace" =>
        if args.length < 2 then ""
        else s"""{"cmd":"move-to-workspace","workspace":${args(1)}}"""
      case "layout" =>
        if args.length < 3 then ""
        else args(1) match
          case "set-master-ratio" =>
            s"""{"cmd":"layout-set-master-ratio","value":${args(2)}}"""
          case "set-master-count" =>
            s"""{"cmd":"layout-set-master-count","value":${args(2)}}"""
          case _ => ""
      case "gamma" =>
        if args.length < 2 then ""
        else args(1) match
          case "reset" => """{"cmd":"gamma-reset"}"""
          case "set" =>
            if args.length < 3 then ""
            else s"""{"cmd":"gamma-set","value":${args(2)}}"""
          case _ => ""
      case "set-idle-timeout" =>
        if args.length < 2 then ""
        else s"""{"cmd":"set-idle-timeout","seconds":${args(1)}}"""
      case "set-screen-off-timeout" =>
        if args.length < 2 then ""
        else s"""{"cmd":"set-screen-off-timeout","seconds":${args(1)}}"""
      case _ => ""

  private def printUsage(): Unit =
    stdio.fprintf(stdio.stderr, c"Usage: swcmsg <command> [args]\n")
    stdio.fprintf(stdio.stderr, c"Commands:\n")
    stdio.fprintf(stdio.stderr, c"  list-windows\n")
    stdio.fprintf(stdio.stderr, c"  get-focused\n")
    stdio.fprintf(stdio.stderr, c"  close-focused\n")
    stdio.fprintf(stdio.stderr, c"  exit\n")
    stdio.fprintf(stdio.stderr, c"  spawn <program> [args...]\n")
    stdio.fprintf(stdio.stderr, c"  get-status\n")
    stdio.fprintf(stdio.stderr, c"  list-outputs\n")
    stdio.fprintf(stdio.stderr, c"  get-focused-output\n")
    stdio.fprintf(stdio.stderr, c"  focus-output <index>\n")
    stdio.fprintf(stdio.stderr, c"  move-to-output <index>\n")
    stdio.fprintf(stdio.stderr, c"  switch-workspace <1-9>\n")
    stdio.fprintf(stdio.stderr, c"  move-to-workspace <1-9>\n")
    stdio.fprintf(stdio.stderr, c"  layout set-master-ratio <ratio>\n")
    stdio.fprintf(stdio.stderr, c"  layout set-master-count <count>\n")
    stdio.fprintf(stdio.stderr, c"  gamma set <0.1-1.0>\n")
    stdio.fprintf(stdio.stderr, c"  gamma reset\n")
    stdio.fprintf(stdio.stderr, c"  set-idle-timeout <seconds>   (0 disables)\n")
    stdio.fprintf(stdio.stderr, c"  set-screen-off-timeout <seconds>   (0 disables)\n")
    stdio.fprintf(stdio.stderr, c"Socket: <XDG_RUNTIME_DIR>/swc.sock\n")
