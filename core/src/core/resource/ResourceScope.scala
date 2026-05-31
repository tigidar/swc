package core.resource

/** Pairs resource acquisition with cleanup declared at acquisition time.
  * Cleanup actions run in reverse registration order (LIFO) on close(). */
final class ResourceScope:
  private var cleanups: List[() => Unit] = Nil

  /** Register a cleanup action. Last registered runs first. */
  def onClose(cleanup: => Unit): Unit =
    cleanups = (() => cleanup) :: cleanups

  /** Track a resource and its release function. Returns the resource. */
  def acquire[A](resource: A)(release: A => Unit): A =
    onClose(release(resource))
    resource

  /**
   * Run all cleanup actions in reverse registration order. Idempotent.
   *
   * If a cleanup throws, subsequent cleanups still run; the first failure is
   * re-thrown after all have been attempted, with later failures attached as
   * suppressed exceptions.
   */
  def close(): Unit =
    val toRun = cleanups
    cleanups = Nil
    var firstFailure: Throwable = null
    toRun.foreach { action =>
      try action()
      catch
        case t: Throwable =>
          if firstFailure == null then firstFailure = t
          else firstFailure.addSuppressed(t)
    }
    if firstFailure != null then throw firstFailure
