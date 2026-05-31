package core.input

import core.geometry.Vec2
import core.windows.WindowId

/** The result of a pointer hit-test: which window surface (if any) the cursor is over. */
sealed trait PointerFocus

/**
 * The pointer is over a known window surface.
 *
 * @param windowId   identity of the window that owns the surface
 * @param surfacePos cursor position in surface-local coordinates
 */
case class Focus(windowId: WindowId, surfacePos: Vec2) extends PointerFocus

/** The pointer is not over any client surface (e.g. over the desktop background). */
case object NoFocus extends PointerFocus
