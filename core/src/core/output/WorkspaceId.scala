package core.output

/**
 * Identity for a workspace (tag) on an output.
 * Fixed range 1-9, matching Super+1 through Super+9 keybindings.
 */
opaque type WorkspaceId = Int

object WorkspaceId:
  def apply(n: Int): WorkspaceId =
    require(n >= 1 && n <= 9, s"WorkspaceId must be 1-9, got $n")
    n

  def value(id: WorkspaceId): Int = id

  /** All valid workspace IDs (1 through 9). */
  val all: List[WorkspaceId] = (1 to 9).toList.map(apply)

  given Ordering[WorkspaceId] = Ordering.Int.asInstanceOf[Ordering[WorkspaceId]]
