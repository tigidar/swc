package core.layout

import core.windows.{WindowList, WindowId}
import core.geometry.Rect

/**
 * Algebra for window layout policies.
 *
 * A `Layout` takes the current window list and the available screen area
 * and produces a mapping from window identity to screen rectangle.
 */
trait Layout:
  def arrange(windows: WindowList, available: Rect): Map[WindowId, Rect]

/**
 * FloatingLayout is the identity layout — windows keep whatever geometry
 * was assigned by the shell. Windows whose geometry lies fully outside
 * `available` are still included; floating windows are not bounds-constrained.
 */
object FloatingLayout extends Layout:
  def arrange(windows: WindowList, available: Rect): Map[WindowId, Rect] =
    windows.ordered.flatMap { id =>
      windows.get(id).map(s => id -> s.geometry)
    }.toMap
