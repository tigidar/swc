package core.state

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import kyo.*
import core.input.*
import core.output.OutputId
import core.windows.WindowId

/**
 * Specification for the screen-off (DPMS) idle logic.
 *
 * The screen-off timeout is the gentle sibling of the auto-lock timeout: after
 * `screenOffTimeoutMs` of no input the compositor powers the outputs off — and
 * does nothing else (no lock, no suspend, no exit) — and the next real user
 * input powers them back on. It is independent of `idleTimeoutMs`, so it runs
 * in all modes whether or not auto-lock is configured.
 *
 * Covers two pure pieces:
 *   - [[IdleHandler.checkScreenOff]] — emits `SetScreenOff(true)` once when the
 *     idle time crosses the timeout, flipping `screenOff`; idempotent thereafter.
 *   - wake handling in [[InputHandler.handle]] — real input clears `screenOff`
 *     and emits `SetScreenOff(false)`; lifecycle events do not.
 *
 * Shares the wlroots monotonic-millisecond clock (CLOCK_MONOTONIC, 32-bit
 * wrapping) and the wrap-safe comparison exercised in [[IdleSpec]].
 */
class ScreenOffSpec extends ScalaCheckSuite:

  private val kid  = KeyboardId("kbd-1")

  /** ~7-minute screen-off timeout, auto-lock left disabled (the default). */
  private val cfg7m = CompositorConfig.default.copy(screenOffTimeoutMs = 420000L)

  private def run[A](cfg: CompositorConfig, state: CompositorState)(
    pipeline: A < EventHandler.Effects
  )(using Frame) = EventHandler.run(cfg, state)(pipeline)

  // ── checkScreenOff ──────────────────────────────────────────────────

  test("checkScreenOff does nothing when the timeout is disabled (0)") {
    val cfg = CompositorConfig.default.copy(screenOffTimeoutMs = 0L)
    val s   = CompositorState.empty.copy(lastActivityMs = 1000L)
    val (ns, effects, _) = run(cfg, s)(IdleHandler.checkScreenOff(nowMs = 10_000_000L))
    assertEquals(effects.toSeq, Seq.empty)
    assertEquals(ns.screenOff, false)
  }

  test("checkScreenOff does nothing before any activity is recorded") {
    val s = CompositorState.empty // lastActivityMs = 0
    val (_, effects, _) = run(cfg7m, s)(IdleHandler.checkScreenOff(nowMs = 10_000_000L))
    assertEquals(effects.toSeq, Seq.empty)
  }

  test("checkScreenOff powers the screen off once the timeout has elapsed") {
    val s = CompositorState.empty.copy(lastActivityMs = 1_000_000L)
    val (ns, effects, _) = run(cfg7m, s)(IdleHandler.checkScreenOff(nowMs = 1_000_000L + 420000L))
    assertEquals(effects.toSeq, Seq(ShellEffect.SetScreenOff(true)))
    assertEquals(ns.screenOff, true)
  }

  test("checkScreenOff does not fire just before the timeout") {
    val s = CompositorState.empty.copy(lastActivityMs = 1_000_000L)
    val (ns, effects, _) = run(cfg7m, s)(IdleHandler.checkScreenOff(nowMs = 1_000_000L + 419999L))
    assertEquals(effects.toSeq, Seq.empty)
    assertEquals(ns.screenOff, false)
  }

  test("checkScreenOff is idempotent: no repeat emit while already off") {
    val s = CompositorState.empty.copy(lastActivityMs = 1_000_000L, screenOff = true)
    val (ns, effects, _) = run(cfg7m, s)(IdleHandler.checkScreenOff(nowMs = 1_000_000L + 999999L))
    assertEquals(effects.toSeq, Seq.empty)
    assertEquals(ns.screenOff, true)
  }

  test("checkScreenOff does not modify lastActivityMs") {
    val s = CompositorState.empty.copy(lastActivityMs = 42L)
    val (ns, _, _) = run(cfg7m, s)(IdleHandler.checkScreenOff(nowMs = 99L))
    assertEquals(ns.lastActivityMs, 42L)
  }

  test("checkScreenOff runs in all modes: fires even with auto-lock disabled") {
    // idleTimeoutMs defaults to 0 (auto-lock off); the screen-off timer is the
    // only idle policy and must still fire.
    assertEquals(cfg7m.idleTimeoutMs, 0L)
    val s = CompositorState.empty.copy(lastActivityMs = 1_000_000L)
    val (ns, effects, _) = run(cfg7m, s)(IdleHandler.checkScreenOff(nowMs = 1_000_000L + 420000L))
    assertEquals(effects.toSeq, Seq(ShellEffect.SetScreenOff(true)))
    assertEquals(ns.screenOff, true)
  }

  property("a wrap-corrected elapsed past the timeout powers the screen off") {
    val last = 0xFFFFFF00L
    forAll(Gen.choose(420000L, 1_000_000L)) { (afterTimeout: Long) =>
      val now = (last + afterTimeout) & 0xFFFFFFFFL
      val (ns, effects, _) = run(cfg7m, CompositorState.empty.copy(lastActivityMs = last))(
        IdleHandler.checkScreenOff(now)
      )
      effects.toSeq == Seq(ShellEffect.SetScreenOff(true)) && ns.screenOff
    }
  }

  // ── wake on input via InputHandler.handle ───────────────────────────

  test("input while the screen is off powers it back on and clears the flag") {
    val off = CompositorState.empty.copy(lastActivityMs = 1_000_000L, screenOff = true)
    val (s, effects, _) = run(cfg7m, off)(
      InputHandler.handle(InputEvent.PointerMoved(10.0, 20.0, time = 2_000_000L, NoFocus))
    )
    assertEquals(s.screenOff, false)
    assertEquals(s.lastActivityMs, 2_000_000L)
    assert(effects.toSeq.contains(ShellEffect.SetScreenOff(false)))
  }

  test("input while the screen is on does not emit a power effect") {
    val on = CompositorState.empty.copy(lastActivityMs = 1_000_000L, screenOff = false)
    val (s, effects, _) = run(cfg7m, on)(
      InputHandler.handle(InputEvent.KeyPress(kid, KeySym(0x0061), KeySym(0x0061), Modifiers.None, 38, time = 2_000_000L))
    )
    assertEquals(s.screenOff, false)
    assert(!effects.toSeq.contains(ShellEffect.SetScreenOff(false)))
    assert(!effects.toSeq.contains(ShellEffect.SetScreenOff(true)))
  }

  test("a lifecycle event does not wake the screen") {
    val off = CompositorState.empty.copy(lastActivityMs = 1_000_000L, screenOff = true)
    val (s, effects, _) = run(cfg7m, off)(
      InputHandler.handle(InputEvent.WindowDestroyed(WindowId(0L)))
    )
    assertEquals(s.screenOff, true, "a destroyed window must not power the panels back on")
    assert(!effects.toSeq.contains(ShellEffect.SetScreenOff(false)))
  }
