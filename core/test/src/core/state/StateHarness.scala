package core.state

import kyo.*
import core.output.OutputId

/**
 * Shared fixtures for the `core.state` handler specs: a default config, one
 * output, and the [[EventHandler]] runner threaded through. Keeps each
 * per-handler spec focused on its own assertions (one harness, N specs).
 */
trait StateHarness:

  protected val cfg  = CompositorConfig.default
  protected val out1 = OutputId("HDMI-A-1")

  /** A state with one output (auto-focused), workspace 1 active. */
  protected def stateWithOutput: CompositorState =
    val (s, _, _) = EventHandler.run(cfg, CompositorState.empty)(
      OutputHandler.addOutput(out1, 1920, 1080, 0, 0)
    )
    s

  protected def run[A](state: CompositorState)(pipeline: A < EventHandler.Effects)(using Frame) =
    EventHandler.run(cfg, state)(pipeline)
