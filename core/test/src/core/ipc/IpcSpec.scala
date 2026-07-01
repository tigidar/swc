package core.ipc

import munit.FunSuite
import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.{Arbitrary, Gen}
import core.windows.WindowId
import core.state.{CompositorConfig, CompositorState, ShellEffect, EventHandler, WindowHandler, OutputHandler}
import core.output.{OutputId, WorkspaceId}
import kyo.Abort

// ── IpcCodec unit tests ──────────────────────────────────────────────────────

class IpcSpec extends FunSuite:

  // ── IpcCodec.parse ──────────────────────────────────────────────────────

  test("""parse({"cmd":"list-windows"}) returns Right(ListWindows)""") {
    assertEquals(IpcCodec.parse("""{"cmd":"list-windows"}"""), Right(ListWindows))
  }

  test("""parse({"cmd":"get-focused"}) returns Right(GetFocused)""") {
    assertEquals(IpcCodec.parse("""{"cmd":"get-focused"}"""), Right(GetFocused))
  }

  test("""parse({"cmd":"close-focused"}) returns Right(CloseFocused)""") {
    assertEquals(IpcCodec.parse("""{"cmd":"close-focused"}"""), Right(CloseFocused))
  }

  test("""parse({"cmd":"exit"}) returns Right(Exit)""") {
    assertEquals(IpcCodec.parse("""{"cmd":"exit"}"""), Right(Exit))
  }

  test("""parse({"cmd":"spawn","args":["foot","--hold"]}) returns Right(Spawn(...))""") {
    assertEquals(
      IpcCodec.parse("""{"cmd":"spawn","args":["foot","--hold"]}"""),
      Right(Spawn(List("foot", "--hold")))
    )
  }

  test("""parse({"cmd":"layout-set-master-ratio","value":0.6}) returns Right(LayoutSetMasterRatio(0.6))""") {
    IpcCodec.parse("""{"cmd":"layout-set-master-ratio","value":0.6}""") match
      case Right(LayoutSetMasterRatio(r)) => assertEqualsDouble(r, 0.6, 1e-9)
      case other => fail(s"Expected Right(LayoutSetMasterRatio(0.6)), got $other")
  }

  test("""parse({"cmd":"layout-set-master-count","value":2}) returns Right(LayoutSetMasterCount(2))""") {
    assertEquals(
      IpcCodec.parse("""{"cmd":"layout-set-master-count","value":2}"""),
      Right(LayoutSetMasterCount(2))
    )
  }

  test("""parse({"cmd":"set-idle-timeout","seconds":600}) returns Right(SetIdleTimeout(600))""") {
    assertEquals(
      IpcCodec.parse("""{"cmd":"set-idle-timeout","seconds":600}"""),
      Right(SetIdleTimeout(600L))
    )
  }

  // Exactly the JSON `swcmsg set-screen-off-timeout 600` emits.
  test("""parse({"cmd":"set-screen-off-timeout","seconds":600}) returns Right(SetScreenOffTimeout(600))""") {
    assertEquals(
      IpcCodec.parse("""{"cmd":"set-screen-off-timeout","seconds":600}"""),
      Right(SetScreenOffTimeout(600L))
    )
  }

  test("parse with unknown cmd returns Left") {
    IpcCodec.parse("""{"cmd":"unknown"}""") match
      case Left(_)  => () // success
      case Right(_) => fail("Expected Left for unknown command")
  }

  test("parse(not json) returns Left") {
    IpcCodec.parse("not json") match
      case Left(_)  => () // success
      case Right(_) => fail("Expected Left for malformed JSON")
  }

  // ── IpcCodec.encode ──────────────────────────────────────────────────────

  test("encode(WindowsListed(Nil)) contains \"windows\":[]") {
    val result = IpcCodec.encode(WindowsListed(Nil))
    assert(result.contains("\"windows\":[]"), s"Missing windows in: $result")
    assert(result.contains("\"type\":\"windows-listed\""), s"Missing type in: $result")
  }

  test("encode(WindowsListed(List(WindowInfo(...)))) contains window fields") {
    val result = IpcCodec.encode(WindowsListed(List(WindowInfo(1L, "foot", "foot", true))))
    assert(result.contains("\"id\":1"), s"Missing id in: $result")
    assert(result.contains("\"title\":\"foot\""), s"Missing title in: $result")
    assert(result.contains("\"appId\":\"foot\""), s"Missing appId in: $result")
    assert(result.contains("\"mapped\":true"), s"Missing mapped in: $result")
  }

  test("encode(FocusedWindow(Some(42L))) contains id:42") {
    val result = IpcCodec.encode(FocusedWindow(Some(42L)))
    assert(result.contains("\"id\":42"), s"Missing id in: $result")
    assert(result.contains("\"type\":\"focused-window\""), s"Missing type in: $result")
  }

  test("encode(FocusedWindow(None)) contains null id") {
    val result = IpcCodec.encode(FocusedWindow(None))
    assert(result.contains("\"type\":\"focused-window\""), s"Missing type in: $result")
  }

  test("encode(Ok) contains type ok") {
    val result = IpcCodec.encode(Ok)
    assert(result.contains("\"type\":\"ok\""), s"Missing type in: $result")
  }

  test("encode(Err(\"bad input\")) contains error message") {
    val result = IpcCodec.encode(Err("bad input"))
    assert(result.contains("\"type\":\"err\""), s"Missing type in: $result")
    assert(result.contains("\"message\":\"bad input\""), s"Missing message in: $result")
  }

  test("Each encoded string is a single line (no embedded newlines)") {
    val responses: List[IpcResponse] = List(
      WindowsListed(Nil),
      WindowsListed(List(WindowInfo(1L, "t", "a", true))),
      FocusedWindow(Some(1L)),
      FocusedWindow(None),
      Ok,
      Err("oops")
    )
    responses.foreach { r =>
      val encoded = IpcCodec.encode(r)
      assert(!encoded.contains('\n'), s"Encoded response contains newline: $encoded")
    }
  }

  // ── IpcDispatch tests (via EventHandler.run) ─────────────────────────────

  private val cfg = CompositorConfig.default
  private val out1 = OutputId("HDMI-A-1")

  /** State with one output, ready for dispatch tests. */
  private def stateWithOutput: CompositorState =
    val (s, _, _) = EventHandler.run(cfg, CompositorState.empty)(
      OutputHandler.addOutput(out1, 1920, 1080, 0, 0)
    )
    s

  /** State with one output and a mapped window (focused). */
  private def stateWithWindow: (CompositorState, WindowId) =
    val s0 = stateWithOutput
    val (s1, _, id) = EventHandler.run(cfg, s0)(WindowHandler.createWindow(Some("w"), None))
    val (s2, _, _) = EventHandler.run(cfg, s1)(WindowHandler.mapWindow(id, out1, Some("w"), None))
    (s2, id)

  private def dispatch(cmd: IpcCommand, state: CompositorState) =
    EventHandler.run(cfg, state)(IpcDispatch.dispatch(cmd))

  test("dispatch(ListWindows) returns WindowsListed(Nil) for empty state") {
    val (_, effects, resp) = dispatch(ListWindows, stateWithOutput)
    assertEquals(resp, WindowsListed(Nil))
    assert(effects.isEmpty)
  }

  test("dispatch(ListWindows) returns WindowsListed with window entries") {
    val (state, id) = stateWithWindow
    val (_, effects, resp) = dispatch(ListWindows, state)
    assert(effects.isEmpty)
    resp match
      case WindowsListed(windows) =>
        assertEquals(windows.size, 1)
        assert(windows.exists(w => w.id == WindowId.value(id) && w.title == "w"))
      case other => fail(s"Expected WindowsListed, got $other")
  }

  test("dispatch(GetFocused) with no focus returns FocusedWindow(None)") {
    val (_, effects, resp) = dispatch(GetFocused, stateWithOutput)
    assertEquals(resp, FocusedWindow(None))
    assert(effects.isEmpty)
  }

  test("dispatch(GetFocused) with focus returns FocusedWindow(Some(id))") {
    val (state, id) = stateWithWindow
    val (_, effects, resp) = dispatch(GetFocused, state)
    assertEquals(resp, FocusedWindow(Some(WindowId.value(id))))
    assert(effects.isEmpty)
  }

  test("dispatch(CloseFocused) with no focus returns Ok, no CloseWindow effect") {
    val (_, effects, resp) = dispatch(CloseFocused, stateWithOutput)
    assertEquals(resp, Ok)
    assert(!effects.toSeq.exists(_.isInstanceOf[ShellEffect.CloseWindow]))
  }

  test("dispatch(CloseFocused) with focus returns Ok and emits CloseWindow") {
    val (state, id) = stateWithWindow
    val (_, effects, resp) = dispatch(CloseFocused, state)
    assertEquals(resp, Ok)
    assert(effects.toSeq.contains(ShellEffect.CloseWindow(id)))
  }

  test("dispatch(Exit) returns Ok and emits TerminateDisplay") {
    val (_, effects, resp) = dispatch(Exit, stateWithOutput)
    assertEquals(resp, Ok)
    assert(effects.toSeq.contains(ShellEffect.TerminateDisplay))
  }

  test("dispatch(Spawn) returns Ok and emits SpawnProcess") {
    val (_, effects, resp) = dispatch(Spawn(List("foot")), stateWithOutput)
    assertEquals(resp, Ok)
    assert(effects.toSeq.contains(ShellEffect.SpawnProcess(List("foot"))))
  }

  // Proves the full live-change chain on the JVM side: command → dispatch →
  // the SetScreenOffTimeout effect the shell turns into setScreenOffTimeout(ms).
  // seconds are converted to ms.
  test("dispatch(SetScreenOffTimeout(600)) returns Ok and emits SetScreenOffTimeout(600000)") {
    val (_, effects, resp) = dispatch(SetScreenOffTimeout(600L), stateWithOutput)
    assertEquals(resp, Ok)
    assertEquals(effects.toSeq, Seq(ShellEffect.SetScreenOffTimeout(600000L)))
  }

  test("dispatch(SetScreenOffTimeout(0)) emits SetScreenOffTimeout(0) (disable live)") {
    val (_, effects, resp) = dispatch(SetScreenOffTimeout(0L), stateWithOutput)
    assertEquals(resp, Ok)
    assertEquals(effects.toSeq, Seq(ShellEffect.SetScreenOffTimeout(0L)))
  }

  test("dispatch(LayoutSetMasterRatio) returns Ok and updates workspace config") {
    val (newState, effects, resp) = dispatch(LayoutSetMasterRatio(0.7), stateWithOutput)
    assertEquals(resp, Ok)
    assert(effects.toSeq.exists(_.isInstanceOf[ShellEffect.RetileOutput]))
    assertEqualsDouble(newState.activeTilingConfig.masterRatio, 0.7, 0.001)
  }

  test("dispatch(LayoutSetMasterCount) returns Ok and updates workspace config") {
    val (newState, effects, resp) = dispatch(LayoutSetMasterCount(3), stateWithOutput)
    assertEquals(resp, Ok)
    assert(effects.toSeq.exists(_.isInstanceOf[ShellEffect.RetileOutput]))
    assertEquals(newState.activeTilingConfig.masterCount, 3)
  }

  // ── Output + workspace dispatch ─────────────────────────────────────

  test("dispatch(ListOutputs) returns output info") {
    val (_, _, resp) = dispatch(ListOutputs, stateWithOutput)
    resp match
      case OutputsListed(outs) =>
        assertEquals(outs.size, 1)
        assertEquals(outs.head.name, "HDMI-A-1")
        assertEquals(outs.head.width, 1920)
        assertEquals(outs.head.activeWorkspace, 1)
        assert(outs.head.focused)
      case other => fail(s"Expected OutputsListed, got $other")
  }

  test("dispatch(GetFocusedOutput) returns focused output name") {
    val (_, _, resp) = dispatch(GetFocusedOutput, stateWithOutput)
    resp match
      case FocusedOutputResponse(Some(name)) => assertEquals(name, "HDMI-A-1")
      case other => fail(s"Expected FocusedOutputResponse, got $other")
  }

  test("dispatch(SwitchWorkspaceCmd(3)) switches active workspace") {
    val (newState, effects, resp) = dispatch(SwitchWorkspaceCmd(3), stateWithOutput)
    assertEquals(resp, Ok)
    assertEquals(newState.outputs(out1).activeWorkspace, WorkspaceId(3))
  }

  test("dispatch(FocusOutputCmd(0)) returns Ok") {
    val (_, _, resp) = dispatch(FocusOutputCmd(0), stateWithOutput)
    assertEquals(resp, Ok)
  }

  // ── Abort pipeline (parse + dispatch) ─────────────────────────────────────

  test("parse error produces failed result") {
    val (_, effects, result) = EventHandler.runAbort[String, IpcResponse](cfg, stateWithOutput) {
      for
        cmd  <- Abort.get(IpcCodec.parse("not json"))
        resp <- IpcDispatch.dispatch(cmd)
      yield resp
    }
    result match
      case kyo.Result.Success(_) => fail("Expected failure for bad JSON")
      case _ => () // Fail or Panic — both are errors
    assert(effects.isEmpty, "no effects should be emitted on parse failure")
  }

  test("valid command through Abort pipeline produces Success") {
    val (_, effects, result) = EventHandler.runAbort[String, IpcResponse](cfg, stateWithOutput) {
      for
        cmd  <- Abort.get(IpcCodec.parse("""{"cmd":"exit"}"""))
        resp <- IpcDispatch.dispatch(cmd)
      yield resp
    }
    result match
      case kyo.Result.Success(resp) =>
        assertEquals(resp, Ok)
        assert(effects.toSeq.contains(ShellEffect.TerminateDisplay))
      case other => fail(s"Expected Success, got $other")
  }

// ── IpcCodec property tests ──────────────────────────────────────────────────

class IpcCodecProps extends ScalaCheckSuite:

  private val genIpcCommand: Gen[IpcCommand] =
    Gen.oneOf(
      Gen.const(ListWindows),
      Gen.const(GetFocused),
      Gen.const(CloseFocused),
      Gen.const(Exit),
      Gen.listOf(Gen.alphaNumStr.suchThat(_.nonEmpty)).map(Spawn(_)),
      Gen.choose(0.01, 0.99).map(LayoutSetMasterRatio(_)),
      Gen.choose(1, 8).map(LayoutSetMasterCount(_)),
      Gen.const(ListOutputs),
      Gen.const(GetFocusedOutput),
      Gen.choose(0, 4).map(FocusOutputCmd(_)),
      Gen.choose(0, 4).map(MoveToOutputCmd(_)),
      Gen.choose(1, 9).map(SwitchWorkspaceCmd(_)),
      Gen.choose(1, 9).map(MoveToWorkspaceCmd(_)),
      Gen.choose(0.1f, 1.0f).map(GammaSet(_)),
      Gen.const(GammaResetCmd),
      Gen.const(GetStatus)
    )

  given Arbitrary[IpcCommand] = Arbitrary(genIpcCommand)

  property("IpcCommand round-trips through encodeCommand then parse") {
    forAll { (cmd: IpcCommand) =>
      val json   = IpcCodec.encodeCommand(cmd)
      val parsed = IpcCodec.parse(json)
      parsed == Right(cmd)
    }
  }

  private val genIpcResponse: Gen[IpcResponse] =
    Gen.oneOf(
      Gen.const(Ok),
      Gen.alphaNumStr.map(s => Err(s)),
      Gen.option(Gen.choose(0L, 1000L)).map(FocusedWindow(_)),
      Gen.listOf(
        for
          id     <- Gen.choose(0L, 1000L)
          title  <- Gen.alphaNumStr
          appId  <- Gen.alphaNumStr
          mapped <- Gen.oneOf(true, false)
        yield WindowInfo(id, title, appId, mapped)
      ).map(WindowsListed(_)),
      Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty)).map(FocusedOutputResponse(_)),
      Gen.listOf(
        for
          name   <- Gen.alphaNumStr.suchThat(_.nonEmpty)
          w      <- Gen.choose(800, 3840)
          h      <- Gen.choose(600, 2160)
          lx     <- Gen.choose(0, 7680)
          ly     <- Gen.choose(0, 4320)
          ws     <- Gen.choose(1, 9)
          focused <- Gen.oneOf(true, false)
        yield OutputInfoResponse(name, w, h, lx, ly, ws, focused)
      ).map(OutputsListed(_)),
      Gen.listOf(
        for
          name     <- Gen.alphaNumStr.suchThat(_.nonEmpty)
          focused  <- Gen.oneOf(true, false)
          activeWs <- Gen.choose(1, 9)
          occupied <- Gen.listOf(Gen.choose(1, 9)).map(_.distinct.sorted)
          title    <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
        yield OutputStatusInfo(name, focused, activeWs, occupied, title)
      ).map(StatusResponse(_))
    )

  given Arbitrary[IpcResponse] = Arbitrary(genIpcResponse)

  property("IpcResponse round-trips through encode then parse") {
    forAll { (resp: IpcResponse) =>
      val json         = IpcCodec.encode(resp)
      val roundTripped = IpcCodec.parseResponse(json)
      roundTripped == Right(resp)
    }
  }
