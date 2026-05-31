package core.windows

/**
 * Immutable ordered collection of compositor windows.
 *
 * `stateMap` provides O(1) lookup by WindowId.
 * `insertionOrder` tracks the order in which windows were added,
 * newest first — matching the `windows: List[Ptr[...]]` semantics
 * in the original compositor shell (prepend on add).
 */
case class WindowList private (
  private val stateMap:       Map[WindowId, WindowState],
  private val insertionOrder: List[WindowId]
):

  /** Add a window. If a window with the same id already exists it is replaced. */
  def add(w: WindowState): WindowList =
    val newOrder =
      if stateMap.contains(w.id) then insertionOrder
      else w.id :: insertionOrder
    WindowList(stateMap + (w.id -> w), newOrder)

  /** Remove a window by id. No-op if the id is not present. */
  def remove(id: WindowId): WindowList =
    if !stateMap.contains(id) then this
    else WindowList(stateMap - id, insertionOrder.filterNot(_ == id))

  /**
   * Update the `mapped` flag for an existing window.
   * No-op if the id is not present.
   */
  def setMapped(id: WindowId, mapped: Boolean): WindowList =
    stateMap.get(id) match
      case None    => this
      case Some(s) => WindowList(stateMap + (id -> s.copy(mapped = mapped)), insertionOrder)

  /** Look up a window by id. */
  def get(id: WindowId): Option[WindowState] = stateMap.get(id)

  /** Window ids in insertion order, newest first. */
  def ordered: List[WindowId] = insertionOrder

  /** Number of windows currently in the list. */
  def size: Int = stateMap.size

object WindowList:
  val empty: WindowList = WindowList(Map.empty, Nil)
