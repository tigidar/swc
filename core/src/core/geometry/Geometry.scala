package core.geometry

/** A 2D integer point. */
case class Point(x: Int, y: Int)

/**
 * Non-negative 2D dimensions.
 * Requires w >= 0 and h >= 0; throws IllegalArgumentException otherwise.
 */
case class Size(w: Int, h: Int):
  require(w >= 0, s"Size.w must be >= 0, got $w")
  require(h >= 0, s"Size.h must be >= 0, got $h")

/**
 * An axis-aligned rectangle with integer coordinates.
 * The rectangle covers pixels [x, x+w) × [y, y+h) (half-open intervals).
 */
case class Rect(x: Int, y: Int, w: Int, h: Int):

  /** True when w == 0 or h == 0 (zero-area rectangle). */
  def isEmpty: Boolean = w == 0 || h == 0

  /**
   * Returns true iff point p lies within this rectangle (half-open interval:
   * left/top edges are inclusive, right/bottom edges are exclusive).
   */
  def contains(p: Point): Boolean =
    p.x >= x && p.x < (x + w) &&
    p.y >= y && p.y < (y + h)

  /**
   * Bounding-box union: the smallest Rect enclosing both this and other.
   * If exactly one rect is empty, the other is returned unchanged.
   * If both rects are empty, the canonical empty `Rect(0, 0, 0, 0)` is
   * returned so the operation is commutative and associative on empties.
   */
  def union(other: Rect): Rect =
    if this.isEmpty && other.isEmpty then Rect(0, 0, 0, 0)
    else if this.isEmpty then other
    else if other.isEmpty then this
    else
      val x0 = math.min(x, other.x)
      val y0 = math.min(y, other.y)
      val x1 = math.max(x + w, other.x + other.w)
      val y1 = math.max(y + h, other.y + other.h)
      Rect(x0, y0, x1 - x0, y1 - y0)

  /**
   * Intersection of this and other.
   * Returns None if there is no overlap; Some(rect) with the overlapping area otherwise.
   */
  def intersect(other: Rect): Option[Rect] =
    val x0 = math.max(x, other.x)
    val y0 = math.max(y, other.y)
    val x1 = math.min(x + w, other.x + other.w)
    val y1 = math.min(y + h, other.y + other.h)
    if x1 <= x0 || y1 <= y0 then None
    else Some(Rect(x0, y0, x1 - x0, y1 - y0))

/** A 2D double-precision floating-point vector (used for cursor positions). */
case class Vec2(x: Double, y: Double)
