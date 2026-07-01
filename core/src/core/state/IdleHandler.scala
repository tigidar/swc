package core.state

import kyo.*

/**
 * Pure idle-timeout handlers.
 *
 * Two independent idle policies, both comparing against the monotonic
 * `lastActivityMs` recorded by [[InputHandler.recordActivity]]:
 *
 *   - [[checkIdle]] auto-exits (auto-lock) after `idleTimeoutMs`.
 *   - [[checkScreenOff]] powers the panels off (DPMS) after
 *     `screenOffTimeoutMs`, with nothing destructive — the next input wakes
 *     them.
 *
 * Both share the wrap-safe 32-bit monotonic comparison in [[idleElapsed]],
 * which uses the same clock as wlroots input-event `time_msec`.
 */
object IdleHandler:

  /**
   * Decide whether the session has been idle long enough to auto-exit.
   *
   * `nowMs` is the current monotonic-millisecond time, sharing the same
   * clock as wlroots input-event `time_msec` (CLOCK_MONOTONIC, truncated to
   * 32 bits). Emits [[ShellEffect.TerminateDisplay]] when the time since the
   * last recorded input meets or exceeds the configured timeout.
   *
   * Disabled when `idleTimeoutMs <= 0` or no activity has been recorded yet.
   * The elapsed computation is wrap-safe: both timestamps live in `[0, 2^32)`,
   * so a single 32-bit wrap is corrected by adding 2^32. The timeout (minutes)
   * is far smaller than the ~49-day wrap period, so this is unambiguous.
   */
  def checkIdle(nowMs: Long)(using Frame): Unit < EventHandler.Effects =
    for
      cfg <- Env.get[CompositorConfig]
      s   <- Var.get[CompositorState]
      _   <-
        if cfg.idleTimeoutMs > 0 && s.lastActivityMs > 0 && idleElapsed(s.lastActivityMs, nowMs) >= cfg.idleTimeoutMs
        then Emit.value(ShellEffect.TerminateDisplay)
        else ((): Unit < EventHandler.Effects)
    yield ()

  /** Wrap-safe elapsed milliseconds between two 32-bit monotonic timestamps. */
  def idleElapsed(lastActivityMs: Long, nowMs: Long): Long =
    val d = nowMs - lastActivityMs
    if d < 0 then d + 0x100000000L else d

  /**
   * Decide whether the session has been idle long enough to power the screen
   * off (DPMS). Emits [[ShellEffect.SetScreenOff]]`(true)` and flips
   * `screenOff` to true exactly once, when the idle time first crosses
   * `screenOffTimeoutMs` and the screen is currently on.
   *
   * Unlike [[checkIdle]] this does nothing destructive — no lock, suspend, or
   * exit. It only powers the panels off; the next real user input
   * ([[InputHandler.recordActivity]]) powers them back on. It is deliberately
   * independent of the auto-lock timeout so it runs in all modes (whether or
   * not `idleTimeoutMs` is set).
   *
   * A no-op when `screenOffTimeoutMs <= 0`, no activity has been recorded yet,
   * or the screen is already off. Shares the same wrap-safe 32-bit monotonic
   * comparison as [[checkIdle]].
   */
  def checkScreenOff(nowMs: Long)(using Frame): Unit < EventHandler.Effects =
    for
      cfg <- Env.get[CompositorConfig]
      s   <- Var.get[CompositorState]
      _   <-
        if cfg.screenOffTimeoutMs > 0 && s.lastActivityMs > 0 && !s.screenOff
           && idleElapsed(s.lastActivityMs, nowMs) >= cfg.screenOffTimeoutMs
        then Var.set(s.copy(screenOff = true)).andThen(Emit.value(ShellEffect.SetScreenOff(true)))
        else ((): Unit < EventHandler.Effects)
    yield ()
