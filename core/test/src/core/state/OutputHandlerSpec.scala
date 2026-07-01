package core.state

import munit.FunSuite
import core.output.{OutputId, WorkspaceId}

class OutputHandlerSpec extends FunSuite, StateHarness:

  test("addOutput creates output with 9 empty workspaces") {
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(OutputHandler.addOutput(out1, 1920, 1080, 0, 0))
    assert(s1.outputs.contains(out1))
    assertEquals(s1.outputs(out1).workspaces.size, 9)
    assertEquals(s1.outputs(out1).activeWorkspace, WorkspaceId(1))
  }

  test("first output is auto-focused") {
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(OutputHandler.addOutput(out1, 1920, 1080, 0, 0))
    assertEquals(s1.focusedOutput, Some(out1))
  }

  test("second output does not steal focus") {
    val out2 = OutputId("DP-1")
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(OutputHandler.addOutput(out1, 1920, 1080, 0, 0))
    val (s2, _, _) = run(s1)(OutputHandler.addOutput(out2, 1920, 1080, 1920, 0))
    assertEquals(s2.focusedOutput, Some(out1))
  }
