package core.state

import kyo.*
import core.input.*
import core.output.WorkspaceId

/**
 * Pure keybinding-action interpreter.
 *
 * Translates the [[core.input.Action]] a key resolves to (via the
 * `KeyBindingMap`) into state changes and [[ShellEffect]]s. This is the
 * dispatch hub: most actions delegate to the domain handlers
 * ([[WindowActions]], [[OutputHandler]], [[WorkspaceHandler]],
 * [[TilingHandler]], [[LauncherHandler]]); the media/gamma/help and
 * close-focused actions are handled inline here. Returns whether the key was
 * consumed (`true`) or should fall through to the focused client
 * (`Passthrough`).
 */
object KeyActionHandler:

  def handleKey(event: KeyEvent)(using Frame): Boolean < EventHandler.Effects =
    for
      cfg <- Env.get[CompositorConfig]
      result <- cfg.keyBindings.dispatch(event) match
        case SpawnTerminal   => Emit.value(ShellEffect.SpawnProcess(List(cfg.terminalCmd))).andThen(true)
        case ExitCompositor  => Emit.value(ShellEffect.TerminateDisplay).andThen(true)
        case OpenLauncher    => LauncherHandler.openLauncher.andThen(true)
        case CloseFocused    => handleCloseFocused
        case CycleFocus      => WindowActions.cycleFocus.andThen(true)
        case ToggleFloating  => WindowActions.toggleFloating.andThen(true)
        case ToggleFullscreen => WindowActions.toggleFullscreen.andThen(true)
        case FocusOutput(i)  => OutputHandler.focusOutputByIndex(i).andThen(true)
        case SwitchWorkspace(ws)  => WorkspaceHandler.switchWorkspace(WorkspaceId(ws)).andThen(true)
        case MoveToWorkspace(ws)  => WorkspaceHandler.moveWindowToWorkspace(WorkspaceId(ws)).andThen(true)
        case MoveToOutput(i)      => WorkspaceHandler.moveWindowToOutputByIndex(i).andThen(true)
        case IncreaseMasterRatio  => TilingHandler.adjustMasterRatio(0.05)
        case DecreaseMasterRatio  => TilingHandler.adjustMasterRatio(-0.05)
        case IncreaseMasterCount  => TilingHandler.adjustMasterCount(1)
        case DecreaseMasterCount  => TilingHandler.adjustMasterCount(-1)
        case action: MediaAction  => handleMediaKey(action)
        case action: GammaAction  => handleGammaKey(action)
        case ShowKeybindings      => handleShowKeybindings(cfg)
        case Passthrough          => false: Boolean < EventHandler.Effects
    yield result

  private def handleCloseFocused(using Frame): Boolean < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- s.activeFocusModel.focused match
        case Some(id) => Emit.value(ShellEffect.CloseWindow(id))
        case None     => Emit.value(ShellEffect.ClearKeyboardFocus)
    yield true

  private def handleMediaKey(action: MediaAction)(using Frame): Boolean < Emit[ShellEffect] =
    val effect = action match
      case BrightnessUp      => ShellEffect.AdjustBrightness(10)
      case BrightnessDown    => ShellEffect.AdjustBrightness(-10)
      case KbdBrightnessUp   => ShellEffect.AdjustKbdBrightness(1)
      case KbdBrightnessDown => ShellEffect.AdjustKbdBrightness(-1)
      case VolumeUp    => ShellEffect.SpawnProcess(List("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "5%+"))
      case VolumeDown  => ShellEffect.SpawnProcess(List("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "5%-"))
      case VolumeMute  => ShellEffect.SpawnProcess(List("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"))
      case MicMute     => ShellEffect.SpawnProcess(List("wpctl", "set-mute", "@DEFAULT_AUDIO_SOURCE@", "toggle"))
    Emit.value(effect).andThen(true)

  private def handleGammaKey(action: GammaAction)(using Frame): Boolean < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      newGamma = action match
        case GammaUp    => Math.min(1.0f, s.gammaFactor + 0.1f)
        case GammaDown  => Math.max(0.1f, s.gammaFactor - 0.1f)
        case GammaReset => 1.0f
      _ <- Var.set(s.copy(gammaFactor = newGamma))
      _ <- Emit.value(ShellEffect.SetGamma(newGamma))
    yield true

  private def handleShowKeybindings(cfg: CompositorConfig)(using Frame): Boolean < Emit[ShellEffect] =
    Emit.value(ShellEffect.SpawnProcess(List(
      cfg.terminalCmd, "-T", "SWC Keybindings", "-e", "sh", "-c",
      "echo 'SWC Keybindings'; echo '==============='; echo;" ++
      "echo 'GENERAL'; echo '  Super+Return       Open terminal'; echo '  Super+Space / R    Run launcher'; echo '  Super+Q            Close window'; echo '  Super+Escape       Exit compositor'; echo '  Super+S            This help'; echo;" ++
      "echo 'WINDOW MODE'; echo '  Super+J            Cycle focus'; echo '  Super+Shift+Space  Toggle floating'; echo '  Super+F / F11      Toggle fullscreen'; echo;" ++
      "echo 'TILING'; echo '  Super+H/L          Master ratio'; echo '  Super+Shift+H/L    Master count'; echo;" ++
      "echo 'WORKSPACES'; echo '  Super+1..9         Switch workspace'; echo '  Super+Shift+1..9   Move window'; echo;" ++
      "echo 'OUTPUTS'; echo '  Super+F1..F5       Focus output'; echo '  Super+Shift+F1..F5 Move to output'; echo;" ++
      "echo 'MEDIA KEYS (Fn)'; echo '  Brightness Up/Down  Display backlight'; echo '  Kbd Bright Up/Down  Keyboard backlight'; echo '  Volume Up/Down      Audio volume'; echo '  Mute                Toggle mute'; echo '  Mic Mute            Toggle mic mute'; echo;" ++
      "echo 'GAMMA'; echo '  Ctrl+Super+Up       Gamma +10%'; echo '  Ctrl+Super+Down     Gamma -10%'; echo '  Ctrl+Super+0        Reset gamma'; echo;" ++
      "echo 'RECORDING'; echo '  wf-recorder -f out.mp4'; echo '  wf-recorder --audio -f out.mp4'; echo;" ++
      "echo 'Press any key to close...'; read -n 1"
    ))).andThen(true)
