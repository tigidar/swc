package core.state

import core.input.KeyBindingMap
import core.output.{OutputId, WorkspaceId}

/**
 * Read-only compositor configuration, injected via [[kyo.Env]].
 *
 * These values are set once at startup and do not change during the
 * compositor's lifetime. They are separate from [[CompositorState]]
 * which tracks mutable runtime state.
 */
case class CompositorConfig(
  keyBindings:   KeyBindingMap,
  terminalCmd:   String,
  windowRules:   List[WindowRule] = Nil,
  // Auto-lock idle timeout in milliseconds. When > 0, the compositor exits
  // (returning the session to the TTY login prompt) after this long with no
  // user input. 0 disables idle auto-exit. Set from COMPOSITOR_IDLE_SECONDS.
  idleTimeoutMs: Long = 0L,
  // Screen-off (DPMS) idle timeout in milliseconds. When > 0, the compositor
  // powers all outputs off — and nothing else: no lock, no suspend, no exit —
  // after this long with no user input; the next key/pointer event powers them
  // back on. This is independent of and gentler than `idleTimeoutMs`, and runs
  // "in all modes" whether or not auto-lock is configured. 0 disables. Set from
  // COMPOSITOR_SCREEN_OFF_SECONDS; defaults to ~7 minutes.
  screenOffTimeoutMs: Long = 420000L
)

/**
 * A rule that controls how a new window is placed.
 *
 * When a window maps, rules are checked in order. The first rule
 * whose `appIdPattern` matches the window's appId is applied.
 *
 * @param appIdPattern  regex pattern matched against the window's appId
 * @param output        if Some, assign the window to this output (by name)
 * @param workspace     if Some, assign to this workspace on the target output
 * @param floating      if Some, set the window's floating state
 */
case class WindowRule(
  appIdPattern: String,
  output:       Option[String] = None,
  workspace:    Option[Int] = None,
  floating:     Option[Boolean] = None
):
  private lazy val regex = appIdPattern.r

  /** Test if this rule matches the given appId. */
  def matches(appId: String): Boolean =
    regex.findFirstIn(appId).isDefined

object CompositorConfig:
  val default: CompositorConfig = CompositorConfig(
    keyBindings = KeyBindingMap.default,
    terminalCmd = "foot"
  )
