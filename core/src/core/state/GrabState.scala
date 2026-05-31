package core.state

import core.windows.WindowId
import core.geometry.Rect

/** Pure model of an interactive pointer grab (move/resize). */
enum GrabState:
  case Moving(
    id: WindowId,
    startCursorX: Double, startCursorY: Double,
    startX: Int, startY: Int
  )
  case Resizing(
    id: WindowId,
    startCursorX: Double, startCursorY: Double,
    startX: Int, startY: Int, startW: Int, startH: Int,
    edges: Int
  )

  def windowId: WindowId = this match
    case Moving(id, _, _, _, _)            => id
    case Resizing(id, _, _, _, _, _, _, _) => id

object GrabState:

  /** wlroots resize edge bitmask values. */
  object ResizeEdge:
    inline val Top    = 1
    inline val Bottom = 2
    inline val Left   = 4
    inline val Right  = 8

  /**
   * Compute the new window position during a move grab.
   * Pure function: cursor delta applied to the start position.
   */
  def computeMove(grab: GrabState.Moving, cursorX: Double, cursorY: Double): (Int, Int) =
    val dx = (cursorX - grab.startCursorX).toInt
    val dy = (cursorY - grab.startCursorY).toInt
    (grab.startX + dx, grab.startY + dy)

  /**
   * Compute the new window geometry during a resize grab.
   * Pure function: cursor delta applied per-edge, size clamped to >= 1.
   * Returns (x, y, w, h).
   */
  def computeResize(grab: GrabState.Resizing, cursorX: Double, cursorY: Double): Rect =
    val dx = (cursorX - grab.startCursorX).toInt
    val dy = (cursorY - grab.startCursorY).toInt
    var x = grab.startX; var y = grab.startY
    var w = grab.startW; var h = grab.startH
    if (grab.edges & ResizeEdge.Right) != 0  then w = math.max(1, grab.startW + dx)
    if (grab.edges & ResizeEdge.Left) != 0   then { x = grab.startX + dx; w = math.max(1, grab.startW - dx) }
    if (grab.edges & ResizeEdge.Bottom) != 0 then h = math.max(1, grab.startH + dy)
    if (grab.edges & ResizeEdge.Top) != 0    then { y = grab.startY + dy; h = math.max(1, grab.startH - dy) }
    Rect(x, y, w, h)
