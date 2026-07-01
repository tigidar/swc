package core.state

import munit.FunSuite
import core.windows.WindowId

class WindowHandlerSpec extends FunSuite, StateHarness:

  // ── createWindow + mapWindow ──────────────────────────────────────

  test("createWindow assigns sequential IDs") {
    val s0 = stateWithOutput
    val (s1, _, id1) = run(s0)(WindowHandler.createWindow(Some("first"), None))
    val (s2, _, id2) = run(s1)(WindowHandler.createWindow(Some("second"), None))
    assertEquals(WindowId.value(id1), 0L)
    assertEquals(WindowId.value(id2), 1L)
  }

  test("mapWindow adds window to output's active workspace") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val ws = s2.outputs(out1).active
    assert(ws.windows.get(id).isDefined)
    assertEquals(ws.windows.get(id).get.mapped, true)
    assertEquals(ws.focus.focused, Some(id))
  }

  test("mapWindow emits RetileOutput and SetKeyboardFocus") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (_, effects, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val effectSeq = effects.toSeq
    assert(effectSeq.contains(ShellEffect.RetileOutput(out1)), s"expected RetileOutput in $effectSeq")
    assert(effectSeq.contains(ShellEffect.SetKeyboardFocus(id)), s"expected SetKeyboardFocus in $effectSeq")
  }

  // ── unmapWindow ─────────────────────────────────────────────────────

  test("unmapWindow clears mapped flag and transfers focus") {
    val s0 = stateWithOutput
    val (s1, _, id1) = run(s0)(WindowHandler.createWindow(Some("a"), None))
    val (s2, _, id2) = run(s1)(WindowHandler.createWindow(Some("b"), None))
    val (s3, _, _) = run(s2)(WindowHandler.mapWindow(id1, out1, Some("a"), None))
    val (s4, _, _) = run(s3)(WindowHandler.mapWindow(id2, out1, Some("b"), None))
    val (s5, _, _) = run(s4)(WindowHandler.unmapWindow(id2))
    val ws = s5.outputs(out1).active
    assertEquals(ws.windows.get(id2).get.mapped, false)
    assertEquals(ws.focus.focused, Some(id1))
  }

  test("unmapWindow emits RetileOutput") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val (_, effects, _) = run(s2)(WindowHandler.unmapWindow(id))
    assert(effects.toSeq.exists(_.isInstanceOf[ShellEffect.RetileOutput]))
  }

  // ── destroyWindow ──────────────────────────────────────────────────

  test("destroyWindow removes window from workspace") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, _, _) = run(s2)(WindowHandler.destroyWindow(id))
    assertEquals(s3.outputs(out1).active.windows.get(id), None)
  }

  test("destroyWindow cancels grab on that window") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val grab = GrabState.Moving(id, 0.0, 0.0, 0, 0)
    val s3 = s2.copy(grabState = Some(grab))
    val (s4, _, _) = run(s3)(WindowHandler.destroyWindow(id))
    assertEquals(s4.grabState, None)
  }

  // ── Pipeline composition ───────────────────────────────────────────

  test("multiple operations compose in a single pipeline") {
    val s0 = stateWithOutput
    val (s1, effects, (id1, id2)) = run(s0) {
      for
        a <- WindowHandler.createWindow(Some("first"), None)
        b <- WindowHandler.createWindow(Some("second"), None)
        _ <- WindowHandler.mapWindow(a, out1, Some("first"), None)
        _ <- WindowHandler.mapWindow(b, out1, Some("second"), None)
      yield (a, b)
    }
    val ws = s1.outputs(out1).active
    assertEquals(ws.windows.size, 2)
    assertEquals(ws.focus.focused, Some(id2))
    assertEquals(effects.toSeq.count(_.isInstanceOf[ShellEffect.RetileOutput]), 2)
  }
