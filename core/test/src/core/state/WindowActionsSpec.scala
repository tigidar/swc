package core.state

import munit.FunSuite

class WindowActionsSpec extends FunSuite, StateHarness:

  test("requestFocus sets focus in the correct workspace") {
    val s0 = stateWithOutput
    val (s1, _, id1) = run(s0)(WindowHandler.createWindow(Some("a"), None))
    val (s2, _, id2) = run(s1)(WindowHandler.createWindow(Some("b"), None))
    val (s3, _, _) = run(s2)(WindowHandler.mapWindow(id1, out1, Some("a"), None))
    val (s4, _, _) = run(s3)(WindowHandler.mapWindow(id2, out1, Some("b"), None))
    val (s5, effects, _) = run(s4)(WindowActions.requestFocus(id1))
    val ws = s5.outputs(out1).active
    assertEquals(ws.focus.focused, Some(id1))
    assert(effects.toSeq.contains(ShellEffect.SetKeyboardFocus(id1)))
  }
