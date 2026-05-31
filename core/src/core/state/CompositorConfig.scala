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
  keyBindings:  KeyBindingMap,
  terminalCmd:  String,
  windowRules:  List[WindowRule] = Nil
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
