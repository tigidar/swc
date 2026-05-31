package compositor

import scala.scalanative.unsafe.*
import scala.scalanative.libc.{stdlib, stdio, string}
import scala.scalanative.unsigned.*

import compositor.ffi.{Wayland as _, Wlroots as _, Helpers as _, *}
import compositor.ffi.Wayland.*
import compositor.ffi.Wlroots.*
import compositor.ffi.Helpers.*

import core.geometry.{DamageRegion, Rect, Vec2}
import core.input.{InputEvent, KeyBindingMap, KeyboardId, KeyEvent, KeySym, Modifiers, Pressed, Released,
                   SpawnTerminal, CloseFocused, ExitCompositor, Passthrough,
                   IncreaseMasterRatio, DecreaseMasterRatio, IncreaseMasterCount, DecreaseMasterCount,
                   PointerFocus, Focus, NoFocus}
import core.windows.{WindowId, WindowState, WindowList, FocusModel}
import core.layout.{MasterStackLayout, MasterStackConfig}
import core.state.{CompositorConfig, CompositorState, GrabState, InputHandler, ShellEffect, EventHandler}
import core.output.{OutputId, OutputInfo}
import kyo.Frame

import core.state.GrabState.ResizeEdge

import scala.scalanative.posix.unistd.{fork, execvp}
import scala.scalanative.posix.stdlib.setenv

/** Per-output FFI state: output pointer and damage tracking. */
private[compositor] class OutputCtx(
  val output: Ptr[WlrOutput],
  val id: OutputId,
  var lastDamage: DamageRegion = DamageRegion.empty,
  var listeners: List[Ptr[Byte]] = Nil
)

/** Signal/listener helper: allocate a listener, register it, store context. */
object Listeners:
  import scala.collection.mutable
  private val contexts = mutable.HashMap.empty[Ptr[Byte], AnyRef]

  def listen(signal: Ptr[Byte], callback: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit], ctx: AnyRef = null): Ptr[Byte] =
    val listener = helper_create_listener(callback)
    if ctx != null then contexts(listener) = ctx
    ListenerExt.helper_signal_add(signal, listener)
    listener

  def getContext[A <: AnyRef](listener: Ptr[Byte]): A =
    contexts(listener).asInstanceOf[A]

  def remove(listener: Ptr[Byte]): Unit =
    contexts.remove(listener)
    helper_destroy_listener(listener)

@extern
private object ListenerExt:
  def helper_signal_add(signal: Ptr[Byte], listener: Ptr[Byte]): Unit = extern // wl_signal_add is a macro, keep C stub
  @name("wlr_seat_set_capabilities")
  def helper_seat_set_capabilities(seat: Ptr[WlrSeat], caps: UInt): Unit = extern

/** Compositor state — single-threaded event loop. */
object Server:
  // FFI handles (set once at init)
  var display: Ptr[WlDisplay] = null
  var backend: Ptr[WlrBackend] = null
  var renderer: Ptr[WlrRenderer] = null
  var allocator: Ptr[WlrAllocator] = null
  var scene: Ptr[WlrScene] = null
  var outputLayout: Ptr[WlrOutputLayout] = null
  var seat: Ptr[WlrSeat] = null
  var cursor: Ptr[WlrCursor] = null
  var xcursorMgr: Ptr[WlrXcursorManager] = null
  var bgRect: Ptr[Byte] = null // background scene rect

  // Read-only config (set once at startup, injected via Env)
  var config: CompositorConfig = CompositorConfig.default

  // Pure compositor state (managed by EventHandler)
  var wm: CompositorState = CompositorState.empty

  // FFI bridge maps
  var ptrMap: Map[WindowId, Ptr[WlrXdgToplevel]] = Map.empty
  var sceneTreeMap: Map[WindowId, Ptr[WlrSceneTree]] = Map.empty
  var outputPtrMap: Map[OutputId, Ptr[WlrOutput]] = Map.empty

  // Input devices (keyboard pointer + its listener pointers)
  var keyboards: List[(Ptr[WlrKeyboard], List[Ptr[Byte]])] = Nil

  // Layer shell
  var layerShell: Ptr[WlrLayerShellV1] = null
  var layerTrees: Array[Ptr[WlrSceneTree]] = new Array(4)
  var appTree: Ptr[WlrSceneTree] = null

  // Per-output FFI tracking (damage, output ptr)
  var outputCtxs: List[OutputCtx] = Nil

  // FFI focus tracking
  var lastActivatedId: Option[WindowId] = None

  // Pointer constraints
  var pointerConstraints: Ptr[WlrPointerConstraintsV1] = null
  var activeConstraint: Ptr[WlrPointerConstraintV1] = null

  // Relative pointer
  var relativePointerMgr: Ptr[WlrRelativePointerManagerV1] = null

  // Pointer gestures
  var pointerGestures: Ptr[WlrPointerGesturesV1] = null

  // Global listeners (cleaned up on shutdown)
  var globalListeners: List[Ptr[Byte]] = Nil

  // Launcher widget
  var launcherTree: Ptr[WlrSceneTree] = null
  var launcherSceneBuf: Ptr[WlrSceneBuffer] = null
  var launcherBuf: Ptr[WlrBuffer] = null

object Main:
  import Server.*
  import Listeners.listen

  def main(args: Array[String]): Unit =
    stdio.fprintf(stdio.stderr, c"[compositor] starting...\n")

    val termEnv = stdlib.getenv(c"COMPOSITOR_TERM")
    if termEnv != null && string.strlen(termEnv).toInt > 0 then
      config = config.copy(terminalCmd = fromCString(termEnv))

    display = wl_display_create()
    if display == null then fatal(c"failed to create wl_display")

    val loop = wl_display_get_event_loop(display)
    val headlessCount = initBackend(loop)
    val xdgShell = initProtocols()
    initScene()
    initCursor()
    registerGlobalListeners(xdgShell)
    val socket = startBackend(headlessCount)

    if stdlib.getenv(c"COMPOSITOR_AUTOSTART") != null then
      spawnProcess(List(config.terminalCmd))

    IpcServer.init(loop)
    wl_display_run(display)
    shutdown()

  private def initBackend(loop: Ptr[WlEventLoop]): Int =
    val headlessEnv = stdlib.getenv(c"SWC_HEADLESS_OUTPUTS")
    val headlessCount = if headlessEnv != null then
      val s = fromCString(headlessEnv).trim
      try s.toInt catch case _: NumberFormatException => 0
    else 0

    backend = if headlessCount > 0 then
      helper_headless_backend_create(loop)
    else
      wlr_backend_autocreate(loop, null)
    if backend == null then fatal(c"failed to create backend")

    renderer = wlr_renderer_autocreate(backend)
    if renderer == null then fatal(c"failed to create renderer")
    if !wlr_renderer_init_wl_display(renderer, display) then fatal(c"renderer init failed")

    allocator = wlr_allocator_autocreate(backend, renderer)
    if allocator == null then fatal(c"failed to create allocator")
    headlessCount

  private def initProtocols(): Ptr[WlrXdgShell] =
    wlr_compositor_create(display, 5.toUInt, renderer)
    wlr_subcompositor_create(display)
    wlr_data_device_manager_create(display)
    wlr_primary_selection_v1_device_manager_create(display)
    wlr_screencopy_manager_v1_create(display)
    wlr_gamma_control_manager_v1_create(display)
    pointerConstraints = helper_pointer_constraints_create(display)
    relativePointerMgr = helper_relative_pointer_manager_create(display)
    pointerGestures = helper_pointer_gestures_create(display)
    val xdgShell = wlr_xdg_shell_create(display, 3.toUInt)
    seat = wlr_seat_create(display, c"seat0")
    outputLayout = wlr_output_layout_create(display)
    layerShell = helper_layer_shell_create(display)
    xdgShell

  private def initScene(): Unit =
    scene = wlr_scene_create()
    wlr_scene_attach_output_layout(scene, outputLayout)

    val root = helper_scene_get_tree(scene)
    bgRect = helper_scene_rect_create(root, 8192, 8192, 0.15f, 0.15f, 0.2f, 1.0f)

    layerTrees(0) = helper_scene_tree_create(root) // BACKGROUND
    layerTrees(1) = helper_scene_tree_create(root) // BOTTOM
    appTree       = helper_scene_tree_create(root) // application windows
    layerTrees(2) = helper_scene_tree_create(root) // TOP
    launcherTree  = helper_scene_tree_create(root) // LAUNCHER (above TOP, below OVERLAY)
    helper_scene_node_set_enabled(launcherTree.asInstanceOf[Ptr[WlrSceneNode]], 0)
    layerTrees(3) = helper_scene_tree_create(root) // OVERLAY

  private def initCursor(): Unit =
    cursor = helper_cursor_create()
    helper_cursor_attach_output_layout(cursor, outputLayout)
    xcursorMgr = helper_xcursor_manager_create(null, 24)
    helper_xcursor_manager_load(xcursorMgr, 2.0f)
    helper_xcursor_manager_set_cursor_image(xcursorMgr, c"default", cursor)

  private def registerGlobalListeners(xdgShell: Ptr[WlrXdgShell]): Unit =
    globalListeners = List(
      listen(helper_backend_get_new_output(backend), onNewOutput),
      listen(helper_backend_get_new_input(backend), onNewInput),
      listen(helper_xdg_shell_get_new_toplevel(xdgShell), onNewToplevel),
      listen(helper_layer_shell_get_new_surface(layerShell), onNewLayerSurface),
      listen(helper_cursor_get_motion(cursor), onCursorMotion),
      listen(helper_cursor_get_motion_absolute(cursor), onCursorMotionAbsolute),
      listen(helper_cursor_get_button(cursor), onCursorButton),
      listen(helper_cursor_get_axis(cursor), onCursorAxis),
      listen(helper_cursor_get_frame(cursor), onCursorFrame),
      listen(helper_cursor_get_swipe_begin(cursor), onSwipeBegin),
      listen(helper_cursor_get_swipe_update(cursor), onSwipeUpdate),
      listen(helper_cursor_get_swipe_end(cursor), onSwipeEnd),
      listen(helper_cursor_get_pinch_begin(cursor), onPinchBegin),
      listen(helper_cursor_get_pinch_update(cursor), onPinchUpdate),
      listen(helper_cursor_get_pinch_end(cursor), onPinchEnd),
      listen(helper_cursor_get_hold_begin(cursor), onHoldBegin),
      listen(helper_cursor_get_hold_end(cursor), onHoldEnd),
      listen(helper_seat_get_request_set_cursor(seat), onRequestSetCursor),
      listen(helper_seat_get_request_set_selection(seat), onRequestSetSelection),
      listen(helper_seat_get_request_set_primary_selection(seat), onRequestSetPrimarySelection),
      listen(helper_seat_get_start_drag(seat), onStartDrag),
      listen(helper_pointer_constraints_get_new_constraint(pointerConstraints), onNewConstraint)
    )

  private def startBackend(headlessCount: Int): CString =
    val socket = wl_display_add_socket_auto(display)
    if socket == null then fatal(c"failed to add socket")

    if !wlr_backend_start(backend) then fatal(c"failed to start backend")
    setenv(c"WAYLAND_DISPLAY", socket, 1)

    if headlessCount > 0 then
      for _ <- 1 to headlessCount do
        helper_headless_add_output(backend, 1920.toUInt, 1080.toUInt)

    stdio.fprintf(stdio.stderr, c"[compositor] running on WAYLAND_DISPLAY=%s\n", socket)
    stdio.fprintf(stdio.stderr, c"[compositor] keybindings:\n")
    stdio.fprintf(stdio.stderr, c"[compositor]   Super+Return  = launch terminal\n")
    stdio.fprintf(stdio.stderr, c"[compositor]   Super+Q       = close focused window\n")
    stdio.fprintf(stdio.stderr, c"[compositor]   Super+Escape  = exit compositor\n")
    socket

  private def shutdown(): Unit =
    IpcServer.shutdown()
    stdio.fprintf(stdio.stderr, c"[compositor] shutting down — closing %d windows, %d layer surfaces\n",
      ptrMap.size, layerSurfaceCtxs.size)
    ptrMap.values.foreach(ListenerExt2.helper_xdg_toplevel_send_close)
    layerSurfaceCtxs.foreach(ctx => helper_layer_surface_close(ctx.layerSurface))
    globalListeners.foreach(Listeners.remove)
    keyboards.foreach((_, ls) => ls.foreach(Listeners.remove))
    wl_display_flush_clients(display)
    wl_display_destroy_clients(display)
    wl_display_destroy(display)

  // ── Shell effect execution ──────────────────────────────────────────

  private[compositor] def executeEffect(effect: ShellEffect): Unit =
    effect match
      case ShellEffect.Retile               => retile()
      case ShellEffect.RetileOutput(oid)    => retileOutput(oid)
      case ShellEffect.SetKeyboardFocus(id) => focusWindow(id)
      case ShellEffect.ClearKeyboardFocus   =>
        lastActivatedId = None
        helper_seat_keyboard_clear_focus(seat)
      case ShellEffect.SpawnProcess(args)   => if args.nonEmpty then spawnProcess(args)
      case ShellEffect.CloseWindow(id)      =>
        ptrMap.get(id).foreach(ListenerExt2.helper_xdg_toplevel_send_close)
      case ShellEffect.TerminateDisplay     => wl_display_terminate(display)
      case ShellEffect.ShowWindow(id)       =>
        sceneTreeMap.get(id).foreach(st =>
          helper_scene_node_set_enabled(st.asInstanceOf[Ptr[WlrSceneNode]], 1))
      case ShellEffect.HideWindow(id)       =>
        sceneTreeMap.get(id).foreach(st =>
          helper_scene_node_set_enabled(st.asInstanceOf[Ptr[WlrSceneNode]], 0))
      case ShellEffect.WarpCursor(x, y)    =>
        helper_cursor_warp_closest(cursor, null, x.toDouble, y.toDouble)
      case ShellEffect.RaiseWindow(id)     =>
        sceneTreeMap.get(id).foreach(st =>
          helper_scene_node_raise_to_top(st.asInstanceOf[Ptr[WlrSceneNode]]))
      case ShellEffect.SetFullscreen(id, x, y, w, h) =>
        ptrMap.get(id).foreach(toplevel => helper_xdg_toplevel_set_size(toplevel, w, h))
        sceneTreeMap.get(id).foreach { st =>
          val node = st.asInstanceOf[Ptr[WlrSceneNode]]
          helper_scene_node_set_position(node, x, y)
          helper_scene_node_raise_to_top(node)
        }
      case ShellEffect.UnsetFullscreen(id) =>
        () // retile will reposition the window
      case ShellEffect.AdjustBrightness(delta) =>
        adjustBrightness(delta)
      case ShellEffect.AdjustKbdBrightness(delta) =>
        adjustKbdBrightness(delta)
      case ShellEffect.SetGamma(factor) =>
        setGammaAllOutputs(factor)

      case ShellEffect.ShowLauncher =>
        showLauncherWidget()

      case ShellEffect.HideLauncher =>
        hideLauncherWidget()

      case ShellEffect.UpdateLauncherText(text) =>
        updateLauncherWidget(text)

      case ShellEffect.SetActiveKeyboard(kid) =>
        keyboards.find((kb, _) => KeyboardId(kb.toLong.toHexString) == kid)
          .foreach((kb, _) => helper_seat_set_keyboard(seat, kb))

      case ShellEffect.ForwardKeyToClient(_, time, keycode, pressed) =>
        val st: UInt = if pressed then 1.toUInt else 0.toUInt
        helper_seat_keyboard_notify_key(seat, time.toUInt, keycode.toUInt, st)

      case ShellEffect.PointerEnter(wid, sx, sy) =>
        ptrMap.get(wid).foreach { toplevel =>
          val surface = helper_xdg_toplevel_get_surface(toplevel)
          if surface != null then
            helper_seat_pointer_notify_enter(seat, surface, sx, sy)
        }

      case ShellEffect.PointerMotion(time, sx, sy) =>
        helper_seat_pointer_notify_motion(seat, time.toUInt, sx, sy)

      case ShellEffect.ClearPointerFocus =>
        helper_seat_pointer_clear_focus(seat)

      case ShellEffect.SetDefaultCursor =>
        helper_xcursor_manager_set_cursor_image(xcursorMgr, c"default", cursor)

      case ShellEffect.MoveWindow(id, x, y) =>
        sceneTreeMap.get(id).foreach { st =>
          helper_scene_node_set_position(st.asInstanceOf[Ptr[WlrSceneNode]], x, y)
        }

      case ShellEffect.ResizeWindow(id, x, y, w, h) =>
        sceneTreeMap.get(id).foreach { st =>
          helper_scene_node_set_position(st.asInstanceOf[Ptr[WlrSceneNode]], x, y)
        }
        ptrMap.get(id).foreach { toplevel =>
          helper_xdg_toplevel_set_size(toplevel, w, h)
        }

  // ── Sysfs brightness helpers ─────────────────────────────────────────

  private def readSysfsInt(path: CString): Int =
    import scala.scalanative.posix.unistd.{read as posixRead, close as posixClose}
    import scala.scalanative.posix.fcntl.{open as posixOpen, O_RDONLY}
    val fd = posixOpen(path, O_RDONLY)
    if fd < 0 then return -1
    val buf = stackalloc[Byte](32)
    val n = posixRead(fd, buf, 31.toUSize)
    posixClose(fd)
    if n <= 0 then return -1
    buf(n.toInt) = 0.toByte
    stdlib.strtol(buf, null, 10).toInt

  private def writeSysfsInt(path: CString, value: Int, label: CString): Unit =
    import scala.scalanative.posix.unistd.{write as posixWrite, close as posixClose}
    import scala.scalanative.posix.fcntl.{open as posixOpen, O_WRONLY}
    val fd = posixOpen(path, O_WRONLY)
    if fd < 0 then
      stdio.fprintf(stdio.stderr, c"[compositor] cannot write to %s (permission denied?)\n", label)
      return
    Zone:
      val str = toCString(value.toString)
      posixWrite(fd, str, string.strlen(str))
    posixClose(fd)

  private def adjustSysfsDevice(
    sysClass: String, devices: Array[String], delta: Int,
    computeNew: (Int, Int, Int) => Int, label: CString
  ): Unit =
    var found = false
    var i = 0
    while i < devices.length && !found do
      Zone:
        val maxPath = toCString(s"/sys/class/$sysClass/${devices(i)}/max_brightness")
        val maxVal = readSysfsInt(maxPath)
        if maxVal >= 0 then
          found = true
          val curPath = toCString(s"/sys/class/$sysClass/${devices(i)}/brightness")
          val curVal = readSysfsInt(curPath)
          if curVal >= 0 then
            val newVal = computeNew(curVal, maxVal, delta)
            writeSysfsInt(curPath, newVal, label)
            stdio.fprintf(stdio.stderr, c"[compositor] %s: %d/%d\n", label, newVal, maxVal)
      i += 1
    if !found then
      stdio.fprintf(stdio.stderr, c"[compositor] no %s device found\n", label)

  private val backlightDevices = Array("amdgpu_bl1", "amdgpu_bl0", "intel_backlight", "acpi_video0")
  private val kbdBacklightDevices = Array("tpacpi::kbd_backlight", "asus::kbd_backlight",
    "smc::kbd_backlight", "platform::kbd_backlight", "hid-0003:258A:0090.0001-backlight")

  private def adjustBrightness(deltaPercent: Int): Unit =
    adjustSysfsDevice("backlight", backlightDevices, deltaPercent,
      (cur, max, d) =>
        val step = (max * Math.abs(d)) / 100
        Math.max(1, Math.min(max, cur + (if d > 0 then step else -step))),
      c"brightness")

  private def adjustKbdBrightness(delta: Int): Unit =
    adjustSysfsDevice("leds", kbdBacklightDevices, delta,
      (cur, max, d) => Math.max(0, Math.min(max, cur + d)),
      c"kbd backlight")

  // ── Gamma control ───────────────────────────────────────────────────

  private def setGammaAllOutputs(factor: Float): Unit =
    outputPtrMap.foreach { case (oid, output) =>
      val ok = helper_output_set_gamma_brightness(output, factor)
      if ok != 0 then
        stdio.fprintf(stdio.stderr, c"[compositor] gamma: %.1f\n", factor.toDouble)
      else
        stdio.fprintf(stdio.stderr, c"[compositor] gamma: failed to set on output\n")
    }

  // ── Process spawning ────────────────────────────────────────────────

  private def spawnProcess(args: List[String]): Unit =
    stdio.fprintf(stdio.stderr, c"[compositor] spawning process\n")
    val pid = fork()
    if pid == 0 then
      Zone:
        val argc = args.length
        val argv = stackalloc[CString]((argc + 1).toUSize)
        args.zipWithIndex.foreach { case (arg, i) =>
          argv(i) = toCString(arg)
        }
        argv(argc) = null
        execvp(argv(0), argv)
      scala.scalanative.posix.unistd._exit(1)

  // ── Output helpers ──────────────────────────────────────────────────

  /** Look up OutputId from a wlroots output pointer. */
  private def outputIdFromPtr(output: Ptr[WlrOutput]): Option[OutputId] =
    if output == null then None
    else
      val name = helper_output_get_name(output)
      if name != null then Some(OutputId(fromCString(name))) else None

  /** Look up OutputId at cursor position. */
  private def outputIdAtCursor: Option[OutputId] =
    val out = helper_output_layout_output_at(outputLayout,
      helper_cursor_get_x(cursor), helper_cursor_get_y(cursor))
    outputIdFromPtr(out).orElse(wm.outputOrder.headOption)

  // ── Output handling ─────────────────────────────────────────────────

  private val onNewOutput: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val output = data.asInstanceOf[Ptr[WlrOutput]]
      val name = helper_output_get_name(output)
      stdio.fprintf(stdio.stderr, c"[compositor] new output: %s\n", name)

      helper_output_init_render(output, allocator, renderer)

      val state = helper_output_state_create()
      helper_output_state_set_enabled(state, 1)
      val mode = helper_output_get_preferred_mode(output)
      if mode != null then helper_output_state_set_mode(state, mode)
      helper_output_commit_state(output, state)
      helper_output_state_destroy(state)

      helper_output_layout_add_auto(outputLayout, output)
      helper_scene_output_create(scene, output)

      val w = helper_output_get_width(output)
      val h = helper_output_get_height(output)
      val lx = helper_output_layout_get_x(outputLayout, output)
      val ly = helper_output_layout_get_y(outputLayout, output)
      val oid = OutputId(fromCString(name))

      // Register in FFI bridge maps
      outputPtrMap = outputPtrMap + (oid -> output)
      val octx = new OutputCtx(output, oid)
      outputCtxs = outputCtxs :+ octx

      // Register in pure state (creates 9 workspaces, auto-focuses if first)
      val (ns, effs, _) = EventHandler.run(config, wm)(
        InputHandler.handle(InputEvent.OutputAdded(oid, w, h, lx, ly))
      )
      wm = ns

      arrangeAllLayers()
      effs.foreach(executeEffect)
      retileOutput(oid)

      octx.listeners = List(
        listen(helper_output_get_frame(output), onOutputFrame),
        listen(helper_output_get_request_state(output), onOutputRequestState),
        listen(helper_output_get_destroy(output), onOutputDestroy)
      )
      ()

  private val onOutputDestroy: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val output = data.asInstanceOf[Ptr[WlrOutput]]
      outputIdFromPtr(output).foreach { oid =>
        stdio.fprintf(stdio.stderr, c"[compositor] output destroyed: %s\n",
          helper_output_get_name(output))
        // Remove from pure state (reassigns windows to remaining outputs)
        val (ns, effs, _) = EventHandler.run(config, wm)(
          InputHandler.handle(InputEvent.OutputRemoved(oid))
        )
        wm = ns
        effs.foreach(executeEffect)
        // Clean up listeners and FFI bridge maps
        outputCtxs.find(_.id == oid).foreach(_.listeners.foreach(Listeners.remove))
        outputPtrMap = outputPtrMap - oid
        outputCtxs = outputCtxs.filterNot(_.id == oid)
      }

  private val MaxDamageRects = 64

  private val onOutputFrame: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val output = data.asInstanceOf[Ptr[WlrOutput]]
      val sceneOutput = helper_scene_get_output(scene, output)
      if sceneOutput != null then
        val state = helper_output_state_create()
        if helper_scene_output_build_state(sceneOutput, state, null) then
          val buf = stackalloc[CInt]((MaxDamageRects * 4).toUSize)
          val n = helper_output_state_extract_damage(state, buf, MaxDamageRects)
          if n > 0 then
            var rects: List[Rect] = Nil
            var i = n - 1
            while i >= 0 do
              rects = Rect(buf(i*4), buf(i*4+1), buf(i*4+2), buf(i*4+3)) :: rects
              i -= 1
            outputCtxs.find(_.output == output).foreach(_.lastDamage = DamageRegion.fromRects(rects))
          else
            outputCtxs.find(_.output == output).foreach(_.lastDamage = DamageRegion.empty)

          helper_output_commit_state(output, state)
        helper_output_state_destroy(state)
        helper_scene_output_send_frame_done(sceneOutput)

  private val onOutputRequestState: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      ()

  // ── Tiling ──────────────────────────────────────────────────────────

  /** Retile all outputs. */
  private[compositor] def retile(): Unit =
    for (oid, _) <- wm.outputs do
      retileOutput(oid)

  /** Retile a single output based on its active workspace. */
  private[compositor] def retileOutput(oid: OutputId): Unit =
    wm.outputs.get(oid).foreach { outInfo =>
      val ws = outInfo.active
      val tiles = MasterStackLayout.arrange(ws.tilingConfig, ws.windows, outInfo.usableArea)
      for (id, rect) <- tiles do
        ptrMap.get(id).foreach { toplevel =>
          helper_xdg_toplevel_set_size(toplevel, rect.w, rect.h)
        }
        sceneTreeMap.get(id).foreach { st =>
          helper_scene_node_set_position(st.asInstanceOf[Ptr[WlrSceneNode]], rect.x, rect.y)
        }
    }

  // ── XDG Toplevel handling ───────────────────────────────────────────

  private val onNewToplevel: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val toplevel = data.asInstanceOf[Ptr[WlrXdgToplevel]]
      val titlePtr = helper_xdg_toplevel_get_title(toplevel)
      if titlePtr != null then
        stdio.fprintf(stdio.stderr, c"[compositor] new toplevel: %s\n", titlePtr)
      else
        stdio.fprintf(stdio.stderr, c"[compositor] new toplevel (untitled)\n")

      val xdgSurface = helper_xdg_toplevel_get_xdg_surface(toplevel)
      val sceneTree = helper_xdg_surface_create_scene_tree(appTree, xdgSurface)
      helper_scene_tree_set_data(sceneTree, toplevel.asInstanceOf[Ptr[Byte]])

      val titleOpt = if titlePtr != null then Some(fromCString(titlePtr)) else None
      val (newState, effects, id) = EventHandler.run(config, wm)(
        InputHandler.handleNewWindow(titleOpt, None)
      )
      wm = newState
      ptrMap = ptrMap + (id -> toplevel)
      sceneTreeMap = sceneTreeMap + (id -> sceneTree)

      val ctx = new IdPtr(id, toplevel)
      ctx.listeners = List(
        listenCtx(helper_xdg_toplevel_get_map(toplevel), onToplevelMap, ctx),
        listenCtx(helper_xdg_toplevel_get_unmap(toplevel), onToplevelUnmap, ctx),
        listenCtx(helper_xdg_toplevel_get_destroy(toplevel), onToplevelDestroy, ctx),
        listenCtx(helper_xdg_toplevel_get_request_move(toplevel), onRequestMove, ctx),
        listenCtx(helper_xdg_toplevel_get_request_resize(toplevel), onRequestResize, ctx),
        listenCtx(helper_xdg_toplevel_get_request_fullscreen(toplevel), onRequestFullscreen, ctx)
      )

      ListenerExt2.helper_xdg_surface_schedule_configure(toplevel)
      effects.foreach(executeEffect)
      ()

  private def listenCtx(signal: Ptr[Byte], callback: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit], ctx: AnyRef): Ptr[Byte] =
    Listeners.listen(signal, callback, ctx)

  private class IdPtr(
    val id: WindowId,
    val ptr: Ptr[WlrXdgToplevel],
    var listeners: List[Ptr[Byte]] = Nil
  ) extends AnyRef

  private class KbCtx(val kb: Ptr[WlrKeyboard]) extends AnyRef

  private class LayerCtx(
    val sceneSurface: Ptr[WlrSceneLayerSurfaceV1],
    val layerSurface: Ptr[WlrLayerSurfaceV1],
    var currentLayer: Int,
    var listeners: List[Ptr[Byte]] = Nil
  )
  private var layerSurfaceCtxs: List[LayerCtx] = Nil

  private val onToplevelMap: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val ctx = Listeners.getContext[IdPtr](listener)
      stdio.fprintf(stdio.stderr, c"[compositor] toplevel mapped\n")
      // Assign to the output under the cursor (or first output)
      val oid = outputIdAtCursor.getOrElse(OutputId("unknown"))
      val titlePtr = helper_xdg_toplevel_get_title(ctx.ptr)
      val titleOpt = if titlePtr != null then Some(fromCString(titlePtr)) else None
      val (newState, effects, _) = EventHandler.run(config, wm)(
        InputHandler.handle(InputEvent.WindowMapped(ctx.id, oid, titleOpt, None))
      )
      wm = newState
      effects.foreach(executeEffect)

  private val onToplevelUnmap: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val ctx = Listeners.getContext[IdPtr](listener)
      val (newState, effects, _) = EventHandler.run(config, wm)(
        InputHandler.handle(InputEvent.WindowUnmapped(ctx.id))
      )
      wm = newState
      effects.foreach(executeEffect)

  private val onToplevelDestroy: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val ctx = Listeners.getContext[IdPtr](listener)
      val (newState, effects, _) = EventHandler.run(config, wm)(
        InputHandler.handle(InputEvent.WindowDestroyed(ctx.id))
      )
      wm = newState
      effects.foreach(executeEffect)
      if lastActivatedId.contains(ctx.id) then lastActivatedId = None
      ptrMap = ptrMap - ctx.id
      sceneTreeMap = sceneTreeMap - ctx.id
      ctx.listeners.foreach(Listeners.remove)

  // ── Client-requested fullscreen ──────────────────────────────────────

  private val onRequestFullscreen: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], _data: Ptr[Byte]) =>
      val ctx = Listeners.getContext[IdPtr](listener)
      val (newState, effects, _) = EventHandler.run(config, wm)(
        InputHandler.handle(InputEvent.RequestFullscreen(ctx.id))
      )
      wm = newState
      effects.foreach(executeEffect)

  // ── Interactive move/resize ─────────────────────────────────────────

  private val onRequestMove: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], _data: Ptr[Byte]) =>
      val ctx = Listeners.getContext[IdPtr](listener)
      sceneTreeMap.get(ctx.id).foreach { tree =>
        val node = tree.asInstanceOf[Ptr[WlrSceneNode]]
        val event = InputEvent.RequestMove(
          ctx.id,
          helper_cursor_get_x(cursor), helper_cursor_get_y(cursor),
          helper_scene_node_get_x(node), helper_scene_node_get_y(node)
        )
        val (newState, effects, _) = EventHandler.run(config, wm)(InputHandler.handle(event))
        wm = newState
        effects.foreach(executeEffect)
      }

  private val onRequestResize: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val ctx = Listeners.getContext[IdPtr](listener)
      val edges = helper_xdg_toplevel_request_resize_get_edges(data).toInt
      (sceneTreeMap.get(ctx.id), ptrMap.get(ctx.id)) match
        case (Some(tree), Some(toplevel)) =>
          val node = tree.asInstanceOf[Ptr[WlrSceneNode]]
          val event = InputEvent.RequestResize(
            ctx.id,
            edges,
            helper_cursor_get_x(cursor), helper_cursor_get_y(cursor),
            helper_scene_node_get_x(node), helper_scene_node_get_y(node),
            helper_xdg_toplevel_get_width(toplevel), helper_xdg_toplevel_get_height(toplevel)
          )
          val (newState, effects, _) = EventHandler.run(config, wm)(InputHandler.handle(event))
          wm = newState
          effects.foreach(executeEffect)
        case _ =>

  // ── Layer shell handling ────────────────────────────────────────────

  private val onNewLayerSurface: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val surface = data.asInstanceOf[Ptr[WlrLayerSurfaceV1]]
      val ns = helper_layer_surface_get_namespace(surface)
      if ns != null then
        stdio.fprintf(stdio.stderr, c"[layer-shell] new surface: %s\n", ns)
      else
        stdio.fprintf(stdio.stderr, c"[layer-shell] new surface (unnamed)\n")

      if helper_layer_surface_get_output(surface) == null then
        if outputCtxs.nonEmpty then
          helper_layer_surface_set_output(surface, outputCtxs.head.output)
        else
          stdio.fprintf(stdio.stderr, c"[layer-shell] no output available, closing\n")

      if helper_layer_surface_get_output(surface) != null then
        val layer = helper_layer_surface_get_pending_layer(surface).toInt
        val parentTree = if layer >= 0 && layer <= 3 then layerTrees(layerIndex(layer)) else layerTrees(2)
        val sceneSurface = helper_scene_layer_surface_create(parentTree, surface)

        val ctx = new LayerCtx(sceneSurface, surface, layer)
        layerSurfaceCtxs = ctx :: layerSurfaceCtxs

        ctx.listeners = List(
          listenCtx(helper_layer_surface_get_surface_commit(surface), onLayerSurfaceCommit, ctx),
          listenCtx(helper_layer_surface_get_destroy(surface), onLayerSurfaceDestroy, ctx)
        )
      ()

  private def layerIndex(layer: Int): Int = layer match
    case 0 => 0; case 1 => 1; case 2 => 2; case 3 => 3; case _ => 2

  private val onLayerSurfaceCommit: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], _data: Ptr[Byte]) =>
      val ctx = Listeners.getContext[LayerCtx](listener)
      val newLayer = helper_layer_surface_get_layer(ctx.layerSurface).toInt
      if newLayer != ctx.currentLayer then
        val tree = helper_scene_layer_surface_get_tree(ctx.sceneSurface)
        helper_scene_node_reparent(tree.asInstanceOf[Ptr[WlrSceneNode]], layerTrees(layerIndex(newLayer)))
        ctx.currentLayer = newLayer
      arrangeAllLayers()
      retile()

  private val onLayerSurfaceDestroy: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], _data: Ptr[Byte]) =>
      val ctx = Listeners.getContext[LayerCtx](listener)
      val ns = helper_layer_surface_get_namespace(ctx.layerSurface)
      if ns != null then
        stdio.fprintf(stdio.stderr, c"[layer-shell] surface destroyed: %s\n", ns)
      layerSurfaceCtxs = layerSurfaceCtxs.filterNot(_ eq ctx)
      ctx.listeners.foreach(Listeners.remove)
      arrangeAllLayers()
      retile()

  /** Two-pass layer arrangement per-output. Updates pure state usable area. */
  private def arrangeAllLayers(): Unit =
    for octx <- outputCtxs do
      val oidOpt = outputIdFromPtr(octx.output)
      val outInfo = oidOpt.flatMap(wm.outputs.get)
      outInfo.foreach { out =>
        val lx = out.layoutX; val ly = out.layoutY
        val ux = stackalloc[CInt](); !ux = lx
        val uy = stackalloc[CInt](); !uy = ly
        val uw = stackalloc[CInt](); !uw = out.width
        val uh = stackalloc[CInt](); !uh = out.height

        val surfacesForOutput = layerSurfaceCtxs.filter { lctx =>
          helper_layer_surface_get_output(lctx.layerSurface) == octx.output
        }

        for lctx <- surfacesForOutput do
          if helper_layer_surface_get_exclusive_zone(lctx.layerSurface) > 0 then
            helper_scene_layer_surface_configure(lctx.sceneSurface,
              lx, ly, out.width, out.height, ux, uy, uw, uh)

        for lctx <- surfacesForOutput do
          if helper_layer_surface_get_exclusive_zone(lctx.layerSurface) <= 0 then
            helper_scene_layer_surface_configure(lctx.sceneSurface,
              lx, ly, out.width, out.height, ux, uy, uw, uh)

        val usableArea = Rect(!ux, !uy, !uw, !uh)
        // Sync usable area back to pure state via InputHandler
        oidOpt.foreach { oid =>
          val (ns, effs, _) = EventHandler.run(config, wm)(
            InputHandler.handle(InputEvent.UsableAreaChanged(oid, usableArea))
          )
          wm = ns
          effs.foreach(executeEffect)
        }
      }

  /** Activate a window's toplevel and notify the seat of keyboard focus. */
  private def focusWindow(id: WindowId): Unit =
    ptrMap.get(id).foreach { toplevel =>
      lastActivatedId
        .filter(_ != id)
        .flatMap(ptrMap.get)
        .foreach(prev => helper_xdg_toplevel_set_activated(prev, 0))
      helper_xdg_toplevel_set_activated(toplevel, 1)
      lastActivatedId = Some(id)
      val surface = helper_xdg_toplevel_get_surface(toplevel)
      keyboards.headOption.foreach { (kb, _) =>
        helper_seat_keyboard_notify_enter(seat, surface, kb)
      }
    }

  // ── Input handling ──────────────────────────────────────────────────

  private val onNewInput: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val device = data.asInstanceOf[Ptr[WlrInputDevice]]
      val devType = helper_input_device_get_type(device)

      stdio.fprintf(stdio.stderr, c"[compositor] new input device type=%d\n", devType)

      if devType == 0 then
        val kb = helper_keyboard_from_input_device(device)
        stdio.fprintf(stdio.stderr, c"[compositor] new keyboard registered: kb=%p (total=%d)\n", kb, (keyboards.size + 1))
        helper_keyboard_set_keymap(kb)
        helper_keyboard_set_repeat_info(kb, 25, 600)
        val kbCtx = KbCtx(kb)
        val kbListeners = List(
          listen(helper_keyboard_get_key(kb), onKeyboardKey, kbCtx),
          listen(helper_keyboard_get_modifiers(kb), onKeyboardModifiers)
        )
        // Register before dispatch so SetActiveKeyboard effect can resolve the kb pointer.
        keyboards = (kb, kbListeners) :: keyboards
        val kid = KeyboardId(kb.toLong.toHexString)
        val (ns, effs, _) = EventHandler.run(config, wm)(
          InputHandler.handle(InputEvent.KeyboardAdded(kid))
        )
        wm = ns
        effs.foreach(executeEffect)

      else if devType == 1 then
        stdio.fprintf(stdio.stderr, c"[compositor] new pointer\n")
        helper_cursor_attach_input_device(cursor, device)
        helper_configure_touchpad(device)

      ListenerExt.helper_seat_set_capabilities(seat,
        1.toUInt | (if keyboards.nonEmpty then 2.toUInt else 0.toUInt))
      ()

  // ── Launcher widget ───────────────────────────────────────────────

  private val LauncherGlyphHeight = 32 // Spleen 16x32
  private val LauncherPadding = 32     // vertical padding total

  private def showLauncherWidget(): Unit =
    wm.focused.foreach { outInfo =>
      val widgetW = math.min(600, (outInfo.width * 0.4).toInt)
      val widgetH = LauncherGlyphHeight + LauncherPadding

      val x = outInfo.layoutX + (outInfo.width - widgetW) / 2
      val y = outInfo.layoutY + (outInfo.height - widgetH) / 2

      launcherBuf = helper_launcher_buffer_create(widgetW, widgetH)
      Zone:
        helper_launcher_render(launcherBuf, toCString(""), 0, widgetW, widgetH)
      launcherSceneBuf = helper_scene_buffer_create(launcherTree, launcherBuf)
      helper_scene_node_set_position(
        launcherSceneBuf.asInstanceOf[Ptr[WlrSceneNode]], x, y)
      helper_scene_node_set_enabled(launcherTree.asInstanceOf[Ptr[WlrSceneNode]], 1)
    }

  private def hideLauncherWidget(): Unit =
    helper_scene_node_set_enabled(launcherTree.asInstanceOf[Ptr[WlrSceneNode]], 0)
    if launcherSceneBuf != null then
      helper_scene_node_destroy(launcherSceneBuf.asInstanceOf[Ptr[WlrSceneNode]])
      launcherSceneBuf = null
    if launcherBuf != null then
      wlr_buffer_drop(launcherBuf)
      launcherBuf = null

  private def updateLauncherWidget(text: String): Unit =
    if launcherBuf != null && launcherSceneBuf != null then
      wm.focused.foreach { outInfo =>
        val widgetW = math.min(600, (outInfo.width * 0.4).toInt)
        val widgetH = LauncherGlyphHeight + LauncherPadding
        Zone:
          helper_launcher_render(launcherBuf, toCString(text), text.length, widgetW, widgetH)
        helper_scene_buffer_set_buffer(launcherSceneBuf, launcherBuf)
      }

  // ── Keyboard handling ───────────────────────────────────────────────

  private val onKeyboardKey: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val keycode = helper_key_event_get_keycode(data)
      val state = helper_key_event_get_state(data)
      val time = helper_key_event_get_time_msec(data)
      val kb = Listeners.getContext[KbCtx](listener).kb
      stdio.fprintf(stdio.stderr, c"[compositor] onKeyboardKey: kb=%p from KbCtx (keycode=%d state=%d)\n", kb, keycode, state)
      val kid = KeyboardId(kb.toLong.toHexString)

      val realSyms = stackalloc[Ptr[UInt]]()
      val count = helper_keyboard_get_keysyms(kb, keycode, realSyms)
      val sym: UInt = if count > 0 then !(!realSyms) else 0.toUInt
      val rawSym: UInt = ListenerExt2.helper_keyboard_get_raw_keysym(kb, keycode)
      val modMask: UInt = ListenerExt2.helper_keyboard_get_modifier_mask(kb)

      val event: InputEvent =
        if state == 1 then
          InputEvent.KeyPress(
            kid,
            KeySym(sym.toInt),
            KeySym(rawSym.toInt),
            Modifiers.from(modMask.toInt),
            keycode.toInt,
            time.toLong
          )
        else
          InputEvent.KeyRelease(kid, keycode.toInt, time.toLong)

      val (ns, effs, _) = EventHandler.run(config, wm)(InputHandler.handle(event))
      wm = ns
      effs.foreach(executeEffect)

      // Launcher dispatch stays imperative — XKB → LauncherInput conversion
      // needs FFI, so InputHandler swallows launcher keypresses and we route
      // them to LauncherHandler here.
      if wm.launcherText.isDefined && state == 1 && count > 0 then
        val XKB_Return    = 0xff0dL.toUInt
        val XKB_Escape    = 0xff1bL.toUInt
        val XKB_BackSpace = 0xff08L.toUInt

        import core.input.LauncherInput
        val input: LauncherInput | Null =
          if sym == XKB_Return then LauncherInput.Submit
          else if sym == XKB_Escape then LauncherInput.Cancel
          else if sym == XKB_BackSpace then LauncherInput.Backspace
          else
            val utf32 = helper_keysym_to_utf32(sym)
            if utf32 > 0.toUInt && utf32 < 0x110000.toUInt then
              LauncherInput.Character(utf32.toInt.toChar)
            else null

        if input != null then
          val (ns2, effs2, _) = EventHandler.run(config, wm)(
            core.state.LauncherHandler.handleInput(input.asInstanceOf[LauncherInput])
          )
          wm = ns2
          effs2.foreach(executeEffect)

  private val onKeyboardModifiers: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val kb = data.asInstanceOf[Ptr[WlrKeyboard]]
      helper_seat_set_keyboard(seat, kb)
      helper_seat_keyboard_notify_modifiers(seat, kb)

  // ── Cursor handling ─────────────────────────────────────────────────

  /** Update focused output based on current cursor position. */
  private def updateFocusedOutputFromCursor(): Unit =
    outputIdAtCursor.foreach { oid =>
      if !wm.focusedOutput.contains(oid) then
        wm = wm.copy(focusedOutput = Some(oid))
    }

  /** Hit-test the scene graph at `(cx, cy)` and build a pure `PointerFocus`. */
  private def computePointerFocus(cx: Double, cy: Double): PointerFocus =
    val sx = stackalloc[CDouble]()
    val sy = stackalloc[CDouble]()
    val node = helper_scene_node_at(scene, cx, cy, sx, sy)
    if node == null then NoFocus
    else
      val toplevelData = helper_scene_node_get_toplevel_data(node)
      if toplevelData == null then NoFocus
      else
        val toplevelPtr = toplevelData.asInstanceOf[Ptr[WlrXdgToplevel]]
        ptrMap.collectFirst { case (wid, ptr) if ptr == toplevelPtr => wid } match
          case Some(wid) => Focus(wid, Vec2(!sx, !sy))
          case None      => NoFocus

  private def processMotion(time: UInt): Unit =
    // LOCKED pointer constraint (type 0): cursor is frozen — skip focus/motion
    // dispatch. Relative motion was already sent to the client by the caller
    // via helper_relative_pointer_send_relative_motion, which is what games
    // relying on pointer lock consume.
    if activeConstraint != null &&
       helper_pointer_constraint_get_type(activeConstraint) == 0.toUInt then
      return
    updateFocusedOutputFromCursor()
    val cx = helper_cursor_get_x(cursor)
    val cy = helper_cursor_get_y(cursor)
    val event = InputEvent.PointerMoved(cx, cy, time.toLong, computePointerFocus(cx, cy))
    val (ns, effs, _) = EventHandler.run(config, wm)(InputHandler.handle(event))
    wm = ns
    effs.foreach(executeEffect)

  private val onCursorMotion: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val dx = helper_pointer_motion_get_dx(data)
      val dy = helper_pointer_motion_get_dy(data)
      val time = helper_pointer_motion_get_time(data)
      val dev = helper_pointer_motion_get_device(data)
      helper_cursor_move(cursor, dev, dx, dy)
      // Forward relative motion (raw + unaccelerated) for games
      val udx = helper_pointer_motion_get_unaccel_dx(data)
      val udy = helper_pointer_motion_get_unaccel_dy(data)
      val timeUsec = time.toLong * 1000L
      helper_relative_pointer_send_relative_motion(
        relativePointerMgr, seat, timeUsec.toULong, dx, dy, udx, udy)
      processMotion(time)

  private val onCursorMotionAbsolute: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val x = helper_pointer_motion_absolute_get_x(data)
      val y = helper_pointer_motion_absolute_get_y(data)
      val time = helper_pointer_motion_absolute_get_time(data)
      val dev = helper_pointer_motion_absolute_get_device(data)
      helper_cursor_warp_absolute(cursor, dev, x, y)
      processMotion(time)

  private val onCursorButton: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val button = helper_pointer_button_get_button(data)
      val state = helper_pointer_button_get_state(data)
      val time = helper_pointer_button_get_time(data)

      // Pure protocol forwarding — stays imperative per AD-006.
      helper_seat_pointer_notify_button(seat, time, button, state)

      val pressed = state != 0.toUInt
      val cx = helper_cursor_get_x(cursor)
      val cy = helper_cursor_get_y(cursor)
      val event = InputEvent.PointerButton(
        button.toInt, pressed, time.toLong, cx, cy, computePointerFocus(cx, cy)
      )
      val (ns, effs, _) = EventHandler.run(config, wm)(InputHandler.handle(event))
      wm = ns
      effs.foreach(executeEffect)

  private val onCursorAxis: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val time        = helper_pointer_axis_get_time(data)
      val orientation = helper_pointer_axis_get_orientation(data)
      val delta       = helper_pointer_axis_get_delta(data)
      val discrete    = helper_pointer_axis_get_delta_discrete(data)
      val source      = helper_pointer_axis_get_source(data)
      val relDir      = helper_pointer_axis_get_relative_direction(data)
      helper_seat_pointer_notify_axis(seat, time, orientation, delta, discrete, source, relDir)
      helper_seat_pointer_notify_frame(seat)

  private val onCursorFrame: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      helper_seat_pointer_notify_frame(seat)

  private val onRequestSetCursor: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val surface = helper_set_cursor_event_get_surface(data)
      val hx = helper_set_cursor_event_get_hotspot_x(data)
      val hy = helper_set_cursor_event_get_hotspot_y(data)
      helper_cursor_set_surface(cursor, surface, hx, hy)

  private val onRequestSetSelection: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val source = helper_selection_event_get_source(data)
      val serial = helper_selection_event_get_serial(data)
      helper_seat_set_selection(seat, source, serial)

  private val onRequestSetPrimarySelection: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val source = helper_primary_selection_event_get_source(data)
      val serial = helper_primary_selection_event_get_serial(data)
      helper_seat_set_primary_selection(seat, source, serial)

  private val onStartDrag: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val drag = data.asInstanceOf[Ptr[WlrDrag]]
      stdio.fprintf(stdio.stderr, c"[compositor] drag started\n")
      val icon = helper_drag_get_icon(drag)
      if icon != null then
        val root = helper_scene_get_tree(scene)
        helper_scene_drag_icon_create(root, icon)
        listen(helper_drag_icon_get_destroy(icon), onDragIconDestroy)
      processMotion(0.toUInt)

  private val onDragIconDestroy: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      stdio.fprintf(stdio.stderr, c"[compositor] drag icon destroyed\n")
      Listeners.remove(listener)

  private val onNewConstraint: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val constraint = data.asInstanceOf[Ptr[WlrPointerConstraintV1]]
      stdio.fprintf(stdio.stderr, c"[compositor] new pointer constraint type=%d\n",
        helper_pointer_constraint_get_type(constraint))
      listen(helper_pointer_constraint_get_destroy(constraint), onConstraintDestroy)
      if activeConstraint != null then
        helper_pointer_constraint_send_deactivated(activeConstraint)
      activeConstraint = constraint
      helper_pointer_constraint_send_activated(constraint)

  private val onConstraintDestroy: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      val constraint = data.asInstanceOf[Ptr[WlrPointerConstraintV1]]
      if activeConstraint == constraint then
        activeConstraint = null
      Listeners.remove(listener)

  private val onSwipeBegin: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      helper_pointer_gestures_send_swipe_begin(pointerGestures, seat,
        helper_swipe_begin_get_time(data), helper_swipe_begin_get_fingers(data))

  private val onSwipeUpdate: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      helper_pointer_gestures_send_swipe_update(pointerGestures, seat,
        helper_swipe_update_get_time(data),
        helper_swipe_update_get_dx(data), helper_swipe_update_get_dy(data))

  private val onSwipeEnd: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      helper_pointer_gestures_send_swipe_end(pointerGestures, seat,
        helper_swipe_end_get_time(data), helper_swipe_end_get_cancelled(data))

  private val onPinchBegin: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      helper_pointer_gestures_send_pinch_begin(pointerGestures, seat,
        helper_pinch_begin_get_time(data), helper_pinch_begin_get_fingers(data))

  private val onPinchUpdate: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      helper_pointer_gestures_send_pinch_update(pointerGestures, seat,
        helper_pinch_update_get_time(data),
        helper_pinch_update_get_dx(data), helper_pinch_update_get_dy(data),
        helper_pinch_update_get_scale(data), helper_pinch_update_get_rotation(data))

  private val onPinchEnd: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      helper_pointer_gestures_send_pinch_end(pointerGestures, seat,
        helper_pinch_end_get_time(data), helper_pinch_end_get_cancelled(data))

  private val onHoldBegin: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      helper_pointer_gestures_send_hold_begin(pointerGestures, seat,
        helper_hold_begin_get_time(data), helper_hold_begin_get_fingers(data))

  private val onHoldEnd: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    (listener: Ptr[Byte], data: Ptr[Byte]) =>
      helper_pointer_gestures_send_hold_end(pointerGestures, seat,
        helper_hold_end_get_time(data), helper_hold_end_get_cancelled(data))

  private def fatal(msg: CString): Nothing =
    stdio.fprintf(stdio.stderr, c"[compositor] FATAL: %s\n", msg)
    stdlib.exit(1)
    throw new RuntimeException

@extern
private object ListenerExt2:
  def helper_keyboard_get_modifier_mask(kb: Ptr[WlrKeyboard]): UInt = extern
  def helper_keyboard_get_raw_keysym(kb: Ptr[WlrKeyboard], keycode: UInt): UInt = extern
  @name("wlr_xdg_toplevel_send_close")
  def helper_xdg_toplevel_send_close(toplevel: Ptr[WlrXdgToplevel]): Unit = extern
  def helper_xdg_surface_schedule_configure(toplevel: Ptr[WlrXdgToplevel]): UInt = extern
