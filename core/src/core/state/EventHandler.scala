package core.state

import kyo.*
import core.windows.FocusModel

/**
 * The pure pipeline interpreter and the effect vocabulary shared by every
 * handler in this package.
 *
 * Domain handlers ([[WindowHandler]], [[OutputHandler]], [[WorkspaceHandler]],
 * [[WindowActions]], [[TilingHandler]], [[KeyActionHandler]], [[IdleHandler]],
 * [[KeyboardHandler]], [[PointerHandler]], [[LauncherHandler]]) describe work
 * as `A < Effects` pipelines, with [[InputHandler]] the dispatcher that routes
 * each `InputEvent` to its owner; the compositor shell calls [[run]] /
 * [[runAbort]] to interpret one, getting back the new state, collected
 * effects, and result value.
 *
 * What lives here is only the cross-cutting glue: the [[Effects]] alias, the
 * two runners, the trivial grab-state setters, and the shared
 * [[emitFocusChange]] helper.
 */
object EventHandler:

  /** The full effect set for pipelines. */
  type Effects = Env[CompositorConfig] & Var[CompositorState] & Emit[ShellEffect]

  // ── Runner ──────────────────────────────────────────────────────────

  def run[A](config: CompositorConfig, state: CompositorState)(
    pipeline: A < Effects
  )(using Frame): (CompositorState, Chunk[ShellEffect], A) =
    val handled: (CompositorState, (Chunk[ShellEffect], A)) < Any =
      Var.runTuple(state) {
        Emit.run[ShellEffect] {
          Env.run(config)(pipeline)
        }
      }
    val (newState, (effects, result)) = handled.eval
    (newState, effects, result)

  def runAbort[E: ConcreteTag, A](config: CompositorConfig, state: CompositorState)(
    pipeline: A < (Effects & Abort[E])
  )(using Frame): (CompositorState, Chunk[ShellEffect], Result[E, A]) =
    val handled: (CompositorState, (Chunk[ShellEffect], Result[E, A])) < Any =
      Var.runTuple(state) {
        Emit.run[ShellEffect] {
          Abort.run[E] {
            Env.run(config)(pipeline)
          }
        }
      }
    val (newState, (effects, result)) = handled.eval
    (newState, effects, result)

  // ── Grab state ──────────────────────────────────────────────────────

  def beginGrab(grab: GrabState)(using Frame): Unit < Var[CompositorState] =
    Var.update[CompositorState](s => s.copy(grabState = Some(grab))).unit

  def releaseGrab(using Frame): Unit < Var[CompositorState] =
    Var.update[CompositorState](s => s.copy(grabState = None)).unit

  // ── Shared emit helpers ─────────────────────────────────────────────

  /**
   * Emit the keyboard-focus effect implied by a focus model: focus the
   * focused window, or clear focus when none. Shared by the navigation
   * handlers (output focus, workspace switching) that re-derive focus.
   */
  def emitFocusChange(fm: FocusModel)(using Frame): Unit < Emit[ShellEffect] =
    fm.focused match
      case Some(id) => Emit.value(ShellEffect.SetKeyboardFocus(id))
      case None     => Emit.value(ShellEffect.ClearKeyboardFocus)
