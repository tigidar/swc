package compositor.ffi

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

// Opaque pointer types for Wayland core
type WlDisplay = CStruct0
type WlEventLoop = CStruct0
type WlListener = CStruct0
type WlSignal = CStruct0

@extern
@link("wayland-server")
object Wayland:
  def wl_display_create(): Ptr[WlDisplay] = extern
  def wl_display_destroy(display: Ptr[WlDisplay]): Unit = extern
  def wl_display_run(display: Ptr[WlDisplay]): Unit = extern
  def wl_display_terminate(display: Ptr[WlDisplay]): Unit = extern
  def wl_display_add_socket_auto(display: Ptr[WlDisplay]): CString = extern
  def wl_display_get_event_loop(display: Ptr[WlDisplay]): Ptr[WlEventLoop] = extern
  def wl_display_flush_clients(display: Ptr[WlDisplay]): Unit = extern
  def wl_display_destroy_clients(display: Ptr[WlDisplay]): Unit = extern
