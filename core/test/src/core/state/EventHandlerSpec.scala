package core.state

import munit.FunSuite

/**
 * Tests for the bits that still live on [[EventHandler]] itself — the
 * grab-state setters. The runner is exercised by every other spec via the
 * shared [[StateHarness]]; the domain operations moved to their own specs
 * (WindowHandlerSpec, OutputHandlerSpec, WorkspaceHandlerSpec,
 * WindowActionsSpec, TilingHandler via IpcSpec, KeyActionHandlerSpec,
 * IdleSpec, ScreenOffSpec).
 */
class EventHandlerSpec extends FunSuite, StateHarness:

  test("beginGrab sets grab state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val grab = GrabState.Moving(id, 100.0, 200.0, 10, 20)
    val (s2, _, _) = run(s1)(EventHandler.beginGrab(grab))
    assertEquals(s2.grabState, Some(grab))
  }

  test("releaseGrab clears grab state") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val grab = GrabState.Moving(id, 100.0, 200.0, 10, 20)
    val s2 = s1.copy(grabState = Some(grab))
    val (s3, _, _) = run(s2)(EventHandler.releaseGrab)
    assertEquals(s3.grabState, None)
  }
