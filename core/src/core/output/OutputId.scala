package core.output

/**
 * Stable identity for a compositor output (monitor).
 * Wraps the wlroots output name (e.g., "HDMI-A-1", "DP-2").
 */
opaque type OutputId = String

object OutputId:
  def apply(name: String): OutputId = name
  def value(id: OutputId): String = id

  given Ordering[OutputId] = Ordering.String.asInstanceOf[Ordering[OutputId]]
