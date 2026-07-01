package core.state

import munit.FunSuite
import core.output.{OutputId, WorkspaceId}

class WorkspaceHandlerSpec extends FunSuite, StateHarness:

  test("switchWorkspace changes active workspace on focused output") {
    val s0 = stateWithOutput
    assertEquals(s0.outputs(out1).activeWorkspace, WorkspaceId(1))
    val (s1, _, _) = run(s0)(WorkspaceHandler.switchWorkspace(WorkspaceId(3)))
    assertEquals(s1.outputs(out1).activeWorkspace, WorkspaceId(3))
  }

  test("switchWorkspace emits HideWindow for old workspace windows") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val (_, effects, _) = run(s2)(WorkspaceHandler.switchWorkspace(WorkspaceId(2)))
    val effectSeq = effects.toSeq
    assert(effectSeq.contains(ShellEffect.HideWindow(id)), s"expected HideWindow in $effectSeq")
  }

  test("moveWindowToWorkspace moves focused window and hides it") {
    val s0 = stateWithOutput
    val (s1, _, id) = run(s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = run(s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    val (s3, effects, _) = run(s2)(WorkspaceHandler.moveWindowToWorkspace(WorkspaceId(2)))
    assertEquals(s3.outputs(out1).workspaces(WorkspaceId(1)).windows.get(id), None)
    assert(s3.outputs(out1).workspaces(WorkspaceId(2)).windows.get(id).isDefined)
    assert(effects.toSeq.contains(ShellEffect.HideWindow(id)))
  }

  test("moveWindowToOutputByIndex moves window between outputs") {
    val out2 = OutputId("DP-1")
    val s0 = CompositorState.empty
    val (s1, _, _) = run(s0)(OutputHandler.addOutput(out1, 1920, 1080, 0, 0))
    val (s2, _, _) = run(s1)(OutputHandler.addOutput(out2, 1920, 1080, 1920, 0))
    val (s3, _, id) = run(s2)(WindowHandler.createWindow(Some("w"), None))
    val (s4, _, _) = run(s3)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    // Window is on out1 workspace 1. Move to output index 1 (out2)
    val (s5, effects, _) = run(s4)(WorkspaceHandler.moveWindowToOutputByIndex(1))
    // Gone from out1
    assertEquals(s5.outputs(out1).active.windows.get(id), None)
    // Present on out2
    assert(s5.outputs(out2).active.windows.get(id).isDefined)
    // Both outputs retiled
    assert(effects.toSeq.contains(ShellEffect.RetileOutput(out1)))
    assert(effects.toSeq.contains(ShellEffect.RetileOutput(out2)))
  }
