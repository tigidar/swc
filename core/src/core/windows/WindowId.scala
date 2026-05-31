package core.windows

/**
 * Stable identity for a compositor window.
 * Assigned by the shell when a toplevel surface is created.
 * The shell maintains a Map[WindowId, Ptr[WlrXdgToplevel]] to
 * recover the C pointer when a decision is to be acted upon.
 */
opaque type WindowId = Long

object WindowId:
  def apply(l: Long): WindowId = l
  def value(id: WindowId): Long = id

  given Ordering[WindowId] = Ordering.Long
