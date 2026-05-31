package compositor.ffi

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

// Opaque pointer types for wlroots
type WlrBackend = CStruct0
type WlrRenderer = CStruct0
type WlrAllocator = CStruct0
type WlrCompositor = CStruct0
type WlrSubcompositor = CStruct0
type WlrXdgShell = CStruct0
type WlrSeat = CStruct0
type WlrOutputLayout = CStruct0
type WlrScene = CStruct0
type WlrSceneOutputLayout = CStruct0
type WlrOutput = CStruct0
type WlrSceneOutput = CStruct0
type WlrOutputState = CStruct0

// wlr_scene has a nested .tree field — we access it via helper
type WlrSceneTree = CStruct0

@extern
@link("wlroots-0.18")
object Wlroots:
  // Backend
  def wlr_backend_autocreate(loop: Ptr[WlEventLoop], session: Ptr[Byte]): Ptr[WlrBackend] = extern
  def wlr_backend_start(backend: Ptr[WlrBackend]): Boolean = extern
  def wlr_backend_destroy(backend: Ptr[WlrBackend]): Unit = extern

  // Renderer
  def wlr_renderer_autocreate(backend: Ptr[WlrBackend]): Ptr[WlrRenderer] = extern
  def wlr_renderer_init_wl_display(renderer: Ptr[WlrRenderer], display: Ptr[WlDisplay]): Boolean = extern

  // Allocator
  def wlr_allocator_autocreate(backend: Ptr[WlrBackend], renderer: Ptr[WlrRenderer]): Ptr[WlrAllocator] = extern

  // Compositor
  def wlr_compositor_create(display: Ptr[WlDisplay], version: UInt, renderer: Ptr[WlrRenderer]): Ptr[WlrCompositor] = extern

  // Subcompositor
  def wlr_subcompositor_create(display: Ptr[WlDisplay]): Ptr[WlrSubcompositor] = extern

  // Data device manager (clipboard / DnD)
  def wlr_data_device_manager_create(display: Ptr[WlDisplay]): Ptr[Byte] = extern
  // Primary selection (middle-click paste)
  def wlr_primary_selection_v1_device_manager_create(display: Ptr[WlDisplay]): Ptr[Byte] = extern

  // XDG Shell
  def wlr_xdg_shell_create(display: Ptr[WlDisplay], version: UInt): Ptr[WlrXdgShell] = extern

  // Seat
  def wlr_seat_create(display: Ptr[WlDisplay], name: CString): Ptr[WlrSeat] = extern

  // Output layout
  def wlr_output_layout_create(display: Ptr[WlDisplay]): Ptr[WlrOutputLayout] = extern

  // Scene graph
  def wlr_scene_create(): Ptr[WlrScene] = extern
  def wlr_scene_attach_output_layout(scene: Ptr[WlrScene], layout: Ptr[WlrOutputLayout]): Ptr[WlrSceneOutputLayout] = extern

  // Screen capture protocols (for wf-recorder, xdg-desktop-portal-wlr)
  def wlr_screencopy_manager_v1_create(display: Ptr[WlDisplay]): Ptr[Byte] = extern

  // Gamma control (for brightness tools like wlsunset)
  def wlr_gamma_control_manager_v1_create(display: Ptr[WlDisplay]): Ptr[Byte] = extern

  // Buffer
  def wlr_buffer_drop(buf: Ptr[WlrBuffer]): Unit = extern
