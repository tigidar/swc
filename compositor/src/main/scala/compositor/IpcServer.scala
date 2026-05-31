package compositor

import scala.scalanative.unsafe.*
import scala.scalanative.libc.{stdio, stdlib, string}
import scala.scalanative.unsigned.*
import scala.scalanative.posix.sys.socket.{socket, bind, listen, accept, sockaddr, socklen_t, AF_UNIX, SOCK_STREAM}
import scala.scalanative.posix.sys.un.sockaddr_un
import scala.scalanative.posix.sys.unOps.*
import scala.scalanative.posix.unistd.{read, write, close, unlink}
import scala.scalanative.posix.fcntl.{fcntl, F_GETFL, F_SETFL, O_NONBLOCK}

import compositor.ffi.{WlEventLoop, WlEventSource, WaylandEventLoop}
import core.ipc.*
import core.resource.ResourceScope
import core.state.EventHandler
import kyo.*

// ── Socket helpers using Scala Native posixlib ──────────────────────────────

private object IpcSocket:

  /** Create, bind, and listen on a Unix domain socket. Returns fd or -1. */
  def createServer(path: String)(using Zone): CInt =
    val pathCStr = toCString(path)
    unlink(pathCStr)

    val fd = socket(AF_UNIX, SOCK_STREAM, 0)
    if fd < 0 then return -1

    val addr = alloc[sockaddr_un]()
    string.memset(addr.asInstanceOf[Ptr[Byte]], 0, sizeof[sockaddr_un].toUSize)
    addr.sun_family = AF_UNIX.toUShort
    string.strncpy(addr.sun_path.at(0), pathCStr, 107.toUSize)

    if bind(fd, addr.asInstanceOf[Ptr[sockaddr]], sizeof[sockaddr_un].toUInt) < 0 then
      close(fd); return -1

    if listen(fd, 16) < 0 then
      close(fd); return -1

    val flags = fcntl(fd, F_GETFL, 0)
    if flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0 then
      close(fd); return -1

    fd

  /** Accept a client connection. Returns client fd or -1. */
  def acceptClient(serverFd: CInt): CInt =
    val fd = accept(serverFd, null, null)
    if fd < 0 then return -1
    val flags = fcntl(fd, F_GETFL, 0)
    if flags >= 0 then fcntl(fd, F_SETFL, flags | O_NONBLOCK)
    fd

  /** Read until newline, EOF, or buffer full. Null-terminates. Returns bytes read. */
  def readLine(fd: CInt, buf: Ptr[Byte], maxlen: CInt): CInt =
    var n = 0
    val c = stackalloc[Byte]()
    var reading = true
    while reading && n < maxlen - 1 do
      val r = read(fd, c, 1.toUSize)
      if r <= 0 then reading = false
      else if !c == '\n'.toByte then reading = false
      else
        !(buf + n) = !c
        n += 1
    !(buf + n) = 0.toByte
    n

  /** Write a string followed by newline. Returns 0 on success, -1 on error. */
  def writeLine(fd: CInt, line: CString): CInt =
    val len = string.strlen(line)
    var written: CSize = 0.toUSize
    while written < len do
      val r = write(fd, line.asInstanceOf[Ptr[Byte]] + written.toLong, len - written)
      if r < 0 then return -1
      written = written + r.toUSize
    val nl = stackalloc[Byte]()
    !nl = '\n'.toByte
    if write(fd, nl, 1.toUSize) < 0 then return -1
    0

  /** Close server fd and remove socket file. */
  def closeServer(fd: CInt, path: String)(using Zone): Unit =
    close(fd)
    unlink(toCString(path))

// ── IPC Server ──────────────────────────────────────────────────────────────

object IpcServer:

  private var loop: Ptr[WlEventLoop] = null
  private var serverFd: CInt = -1
  private var socketPath: String = ""
  // Each client gets a ResourceScope that pairs fd + event source cleanup
  private var clientScopes: Map[CInt, ResourceScope] = Map.empty

  private inline def withZone[A](inline f: Zone ?=> A): A =
    val z = Zone.open()
    try f(using z) finally z.close()

  def init(eventLoop: Ptr[WlEventLoop]): Unit =
    loop = eventLoop

    val runtimeDirPtr = stdlib.getenv(c"XDG_RUNTIME_DIR")
    if runtimeDirPtr == null then
      stdio.fprintf(stdio.stderr, c"[ipc] XDG_RUNTIME_DIR not set; IPC socket disabled\n")
      return

    val runtimeDir = fromCString(runtimeDirPtr)
    socketPath = s"$runtimeDir/swc.sock"

    serverFd = withZone { IpcSocket.createServer(socketPath) }

    if serverFd == -1 then
      stdio.fprintf(stdio.stderr, c"[ipc] failed to create socket\n")
      return

    WaylandEventLoop.addFd(loop, serverFd, onServerReadable, null)
    withZone { stdio.fprintf(stdio.stderr, c"[ipc] listening on %s\n", toCString(socketPath)) }

  def shutdown(): Unit =
    if serverFd != -1 then
      withZone { IpcSocket.closeServer(serverFd, socketPath) }
      serverFd = -1

  // ── Callbacks ─────────────────────────────────────────────────────────────

  private val onServerReadable: CFuncPtr3[CInt, CUnsignedInt, Ptr[Byte], CInt] =
    (fd: CInt, _mask: CUnsignedInt, _data: Ptr[Byte]) =>
      val clientFd = IpcSocket.acceptClient(fd)
      if clientFd == -1 then
        stdio.fprintf(stdio.stderr, c"[ipc] accept failed\n")
      else
        val scope = ResourceScope()
        scope.onClose(close(clientFd))
        val source = WaylandEventLoop.addFd(loop, clientFd, onClientReadable, null)
        scope.onClose(WaylandEventLoop.removeSource(source))
        clientScopes = clientScopes + (clientFd -> scope)
      0

  /** Close the client's ResourceScope (removes event source, then closes fd). */
  private def closeClient(fd: CInt): Unit =
    clientScopes.get(fd).foreach(_.close())
    clientScopes = clientScopes - fd

  private val onClientReadable: CFuncPtr3[CInt, CUnsignedInt, Ptr[Byte], CInt] =
    (fd: CInt, _mask: CUnsignedInt, _data: Ptr[Byte]) =>
      val buf = stackalloc[Byte](4096.toUSize)
      val n   = IpcSocket.readLine(fd, buf, 4096)
      if n <= 0 then
        closeClient(fd)
      else
        val line = fromCString(buf).trim
        // Parse + dispatch in one Abort pipeline
        val (newState, effects, result) = EventHandler.runAbort[String, IpcResponse](Server.config, Server.wm) {
          for
            cmd  <- Abort.get(IpcCodec.parse(line))
            resp <- IpcDispatch.dispatch(cmd)
          yield resp
        }
        result.toEither match
          case Right(resp) =>
            Server.wm = newState
            withZone { IpcSocket.writeLine(fd, toCString(IpcCodec.encode(resp))) }
            closeClient(fd)
            effects.foreach(Main.executeEffect)
          case Left(err) =>
            val msg = err match
              case s: String    => s
              case t: Throwable => t.getMessage
            withZone { IpcSocket.writeLine(fd, toCString(IpcCodec.encode(Err(msg)))) }
            closeClient(fd)
      0
