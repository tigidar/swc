package core.layout

import core.geometry.Rect
import core.windows.{WindowId, WindowList}

/**
 * Immutable configuration for the master-stack tiling layout.
 *
 * @param masterRatio  fraction of the screen width dedicated to the master column (0.0, 1.0)
 * @param masterCount  number of windows placed in the master column (>= 1)
 */
case class MasterStackConfig(masterRatio: Double, masterCount: Int):
  require(masterRatio > 0.0 && masterRatio < 1.0,
    s"masterRatio must be in (0.0, 1.0), got $masterRatio")
  require(masterCount >= 1,
    s"masterCount must be >= 1, got $masterCount")

object MasterStackConfig:
  val default: MasterStackConfig = MasterStackConfig(0.55, 1)

/**
 * Pure master-stack tiling layout.
 *
 * Windows are divided into two columns: a master column on the left and a
 * stack column on the right. When all windows fit within the master count the
 * master column expands to fill the full available width (no stack column is
 * rendered). Only mapped windows are tiled.
 *
 * Heights within each column are distributed using integer remainder
 * distribution: tiles 0..n-2 each get `totalH / n`, tile n-1 gets
 * `totalH - (n-1) * (totalH / n)` so the last tile absorbs any remainder
 * pixel and guarantees exact full coverage.
 */
object MasterStackLayout:

  /**
   * Pure arrange function.
   *
   * Only mapped windows are tiled (AD-008). Unmapped windows are absent from
   * the result map.
   */
  def arrange(
    config:    MasterStackConfig,
    windows:   WindowList,
    available: Rect
  ): Map[WindowId, Rect] =

    val mapped: List[WindowId] =
      windows.ordered.filter(id => windows.get(id).exists(w =>
        w.mapped && !w.floating && !w.fullscreen))

    if mapped.isEmpty then Map.empty
    else
      val masterN = math.min(config.masterCount, mapped.size)
      val (masterIds, stackIds) = mapped.splitAt(masterN)

      if stackIds.isEmpty then
        distributeColumn(masterIds, available.x, available.y, available.w, available.h)
      else
        val masterW = (available.w * config.masterRatio).toInt
        val stackW  = available.w - masterW
        distributeColumn(masterIds, available.x,           available.y, masterW, available.h) ++
        distributeColumn(stackIds,  available.x + masterW, available.y, stackW,  available.h)

  /**
   * Distribute `ids` vertically within a column anchored at (x, y) with the
   * given width and total height.  Uses integer remainder distribution so
   * the last tile absorbs any leftover pixels.
   */
  private def distributeColumn(
    ids:    List[WindowId],
    x:      Int,
    y:      Int,
    w:      Int,
    totalH: Int
  ): Map[WindowId, Rect] =
    val n     = ids.size
    val tileH = totalH / n
    ids.zipWithIndex.map { (id, idx) =>
      val h      = if idx == n - 1 then totalH - (n - 1) * tileH else tileH
      val offset = y + idx * tileH
      id -> Rect(x, offset, w, h)
    }.toMap

  /** Returns a Layout instance backed by this config. */
  def apply(config: MasterStackConfig): Layout =
    new Layout:
      def arrange(windows: WindowList, available: Rect): Map[WindowId, Rect] =
        MasterStackLayout.arrange(config, windows, available)
