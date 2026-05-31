package core.windows

/**
 * Pure focus policy model.
 *
 * Tracks which window (if any) currently has keyboard focus.
 * All transitions return a new FocusModel — this type is immutable.
 */
case class FocusModel(focused: Option[WindowId]):

  /**
   * Request focus for `id`.
   * Sets focused to Some(id) iff the window exists in `windows` and is mapped.
   * Otherwise returns this unchanged.
   */
  def focus(id: WindowId, windows: WindowList): FocusModel =
    windows.get(id) match
      case Some(s) if s.mapped => copy(focused = Some(id))
      case _                   => this

  /**
   * Relinquish focus for `id`.
   *
   * - If `focused != Some(id)`, returns this unchanged (no-op).
   * - Otherwise clears focus and selects the first window from
   *   `windows.ordered` that is not `id` and is mapped.
   * - If no such window exists, `focused` becomes None.
   */
  def unfocus(id: WindowId, windows: WindowList): FocusModel =
    if focused != Some(id) then this
    else
      val next = windows.ordered
        .find(wid => wid != id && windows.get(wid).exists(_.mapped))
      copy(focused = next)

object FocusModel:
  val empty: FocusModel = FocusModel(None)
