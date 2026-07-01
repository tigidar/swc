package core.state

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import kyo.*
import core.input.*
import core.output.OutputId
import core.windows.WindowId

/**
 * Specification for idle auto-lock logic.
 *
 * Covers two pure pieces:
 *   - [[IdleHandler.checkIdle]] — decides whether enough idle time has passed
 *     to emit `TerminateDisplay` (the signal the shell turns into a clean exit
 *     back to the TTY login prompt).
 *   - activity recording in [[InputHandler.handle]] — real user input updates
 *     `lastActivityMs`; window/output lifecycle events must not.
 *
 * The shell drives this with a monotonic-millisecond clock shared with wlroots
 * input-event timestamps (CLOCK_MONOTONIC, 32-bit wrapping).
 */
class IdleSpec extends ScalaCheckSuite:

  private val kid  = KeyboardId("kbd-1")
  private val out1 = OutputId("HDMI-A-1")

  /** Config with a 5-minute idle timeout (the production default). */
  private val cfg5m = CompositorConfig.default.copy(idleTimeoutMs = 300000L)

  private def run[A](cfg: CompositorConfig, state: CompositorState)(
    pipeline: A < EventHandler.Effects
  )(using Frame) = EventHandler.run(cfg, state)(pipeline)

  // ── checkIdle ───────────────────────────────────────────────────────

  test("checkIdle does nothing when idle timeout is disabled (0)") {
    val cfg = CompositorConfig.default // idleTimeoutMs = 0
    val s   = CompositorState.empty.copy(lastActivityMs = 1000L)
    val (_, effects, _) = run(cfg, s)(IdleHandler.checkIdle(nowMs = 10_000_000L))
    assertEquals(effects.toSeq, Seq.empty)
  }

  test("checkIdle does nothing before any activity is recorded (lastActivityMs == 0)") {
    val s = CompositorState.empty // lastActivityMs = 0
    val (_, effects, _) = run(cfg5m, s)(IdleHandler.checkIdle(nowMs = 10_000_000L))
    assertEquals(effects.toSeq, Seq.empty)
  }

  test("checkIdle emits TerminateDisplay once the timeout has elapsed") {
    val s = CompositorState.empty.copy(lastActivityMs = 1_000_000L)
    val (_, effects, _) = run(cfg5m, s)(IdleHandler.checkIdle(nowMs = 1_000_000L + 300000L))
    assertEquals(effects.toSeq, Seq(ShellEffect.TerminateDisplay))
  }

  test("checkIdle does not emit just before the timeout") {
    val s = CompositorState.empty.copy(lastActivityMs = 1_000_000L)
    val (_, effects, _) = run(cfg5m, s)(IdleHandler.checkIdle(nowMs = 1_000_000L + 299999L))
    assertEquals(effects.toSeq, Seq.empty)
  }

  test("checkIdle does not modify lastActivityMs") {
    val s = CompositorState.empty.copy(lastActivityMs = 42L)
    val (ns, _, _) = run(cfg5m, s)(IdleHandler.checkIdle(nowMs = 99L))
    assertEquals(ns.lastActivityMs, 42L)
  }

  // ── idleElapsed (wrap-safe) ─────────────────────────────────────────

  test("idleElapsed is the plain difference when now >= last") {
    assertEquals(IdleHandler.idleElapsed(lastActivityMs = 1000L, nowMs = 1500L), 500L)
  }

  test("idleElapsed corrects a single 32-bit wrap") {
    // last just below 2^32, now just after wrap to a small value.
    val last = 0xFFFFFF00L      // 4294967040
    val now  = 0x000000FFL      // 255  → real elapsed 255 + 256 = 511 ms
    assertEquals(IdleHandler.idleElapsed(last, now), 511L)
  }

  property("idleElapsed is always in [0, 2^32) for 32-bit inputs") {
    val u32 = Gen.choose(0L, 0xFFFFFFFFL)
    forAll(u32, u32) { (last, now) =>
      val e = IdleHandler.idleElapsed(last, now)
      e >= 0L && e < 0x100000000L
    }
  }

  property("a wrap-corrected elapsed past the timeout fires the lock") {
    // Activity near the 32-bit ceiling, now wrapped past it by >= timeout.
    val last = 0xFFFFFF00L
    forAll(Gen.choose(300000L, 1_000_000L)) { (afterTimeout: Long) =>
      val now = (last + afterTimeout) & 0xFFFFFFFFL
      val (_, effects, _) = run(cfg5m, CompositorState.empty.copy(lastActivityMs = last))(
        IdleHandler.checkIdle(now)
      )
      effects.toSeq == Seq(ShellEffect.TerminateDisplay)
    }
  }

  // ── activity recording via InputHandler.handle ──────────────────────

  test("KeyPress records its timestamp as lastActivityMs") {
    val (s, _, _) = run(cfg5m, CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyPress(
        keyboardId = kid,
        keysym     = KeySym(0x0061),
        rawKeysym  = KeySym(0x0061),
        modifiers  = Modifiers.None,
        keycode    = 38,
        time       = 7777L
      ))
    )
    assertEquals(s.lastActivityMs, 7777L)
  }

  test("KeyRelease records its timestamp as lastActivityMs") {
    val (s, _, _) = run(cfg5m, CompositorState.empty)(
      InputHandler.handle(InputEvent.KeyRelease(kid, keycode = 38, time = 8888L))
    )
    assertEquals(s.lastActivityMs, 8888L)
  }

  test("PointerMoved records its timestamp as lastActivityMs") {
    val (s, _, _) = run(cfg5m, CompositorState.empty)(
      InputHandler.handle(InputEvent.PointerMoved(10.0, 20.0, time = 1234L, NoFocus))
    )
    assertEquals(s.lastActivityMs, 1234L)
  }

  test("PointerButton records its timestamp as lastActivityMs") {
    val (s, _, _) = run(cfg5m, CompositorState.empty)(
      InputHandler.handle(InputEvent.PointerButton(
        button = 272, pressed = true, time = 4321L, cursorX = 0.0, cursorY = 0.0, focus = NoFocus
      ))
    )
    assertEquals(s.lastActivityMs, 4321L)
  }

  test("window lifecycle events do not count as activity") {
    val seeded = CompositorState.empty.copy(lastActivityMs = 5000L)
    val (s, _, _) = run(cfg5m, seeded)(
      InputHandler.handle(InputEvent.WindowDestroyed(WindowId(0L)))
    )
    assertEquals(s.lastActivityMs, 5000L, "a destroyed window must not keep the session awake")
  }

  test("an input event after idle resets the deadline (no immediate lock)") {
    // Activity at t=1_000_000; then a keypress at a later time; checkIdle just
    // after the new activity must not fire.
    val s0 = CompositorState.empty.copy(lastActivityMs = 1_000_000L)
    val (s1, _, _) = run(cfg5m, s0)(
      InputHandler.handle(InputEvent.KeyPress(kid, KeySym(0x0061), KeySym(0x0061), Modifiers.None, 38, time = 2_000_000L))
    )
    val (_, effects, _) = run(cfg5m, s1)(IdleHandler.checkIdle(nowMs = 2_000_100L))
    assertEquals(effects.toSeq, Seq.empty)
  }
