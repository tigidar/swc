package compositor.ffi

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Opaque handle for a Wayland event source. */
type WlEventSource = CStruct0

/**
 * C helpers for wayland event loop fd registration.
 *
 * These go through wlr_helpers.c because wl_event_loop_add_fd takes a
 * C function pointer (wl_event_loop_fd_func_t) and Scala Native's
 * CFuncPtr ABI doesn't always match what the C side expects when passed
 * directly via @extern @link.
 */
@extern
object WaylandEventLoop:
  @name("helper_event_loop_add_fd")
  def addFd(
    loop:     Ptr[WlEventLoop],
    fd:       CInt,
    callback: CFuncPtr3[CInt, CUnsignedInt, Ptr[Byte], CInt],
    data:     Ptr[Byte]
  ): Ptr[WlEventSource] = extern

  @name("wl_event_source_remove")
  def removeSource(source: Ptr[WlEventSource]): CInt = extern
