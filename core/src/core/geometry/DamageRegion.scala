package core.geometry

/**
 * An opaque damage region represented as a list of rectangles.
 * The list is not normalised (rects may overlap); it tracks which screen
 * areas have been damaged and need repainting.
 */
opaque type DamageRegion = List[Rect]

object DamageRegion:

  /** An empty damage region — nothing damaged. */
  val empty: DamageRegion = Nil

  /** Create a damage region covering exactly one rectangle. */
  def fromRect(r: Rect): DamageRegion = List(r)

  /** Create a damage region from multiple rectangles. */
  def fromRects(rs: List[Rect]): DamageRegion = rs

  extension (self: DamageRegion)

    /** The underlying rectangles. */
    def rects: List[Rect] = self

    /** Number of rectangles in the region. */
    def size: Int = self.size

    /**
     * Union of two damage regions: concatenate their rect lists.
     * The result covers all areas covered by either region.
     */
    def union(other: DamageRegion): DamageRegion = self ++ other

    /**
     * True when every rect in the region is empty (zero-area), or the
     * region contains no rects at all.
     */
    def isEmpty: Boolean = self.forall(_.isEmpty)

    /** Smallest Rect enclosing all damage, or None if empty. */
    def boundingBox: Option[Rect] =
      val nonEmpty = self.filterNot(_.isEmpty)
      if nonEmpty.isEmpty then None
      else Some(nonEmpty.reduce(_ union _))

    /** Total area covered (may double-count overlapping regions). */
    def area: Long =
      self.foldLeft(0L)((acc, r) => acc + r.w.toLong * r.h.toLong)
