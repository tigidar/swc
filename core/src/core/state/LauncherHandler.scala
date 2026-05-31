package core.state

import kyo.*
import core.input.LauncherInput

/**
 * Pure launcher input handling.
 *
 * Manages the launcher text buffer and emits ShellEffects for
 * the compositor shell to show/hide/update the launcher widget.
 */
object LauncherHandler:

  def openLauncher(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- if s.launcherText.isDefined then closeLauncher  // toggle off
           else
             for
               _ <- Var.set(s.copy(launcherText = Some("")))
               _ <- Emit.value(ShellEffect.ShowLauncher)
             yield ()
    yield ()

  def closeLauncher(using Frame): Unit < (Var[CompositorState] & Emit[ShellEffect]) =
    for
      s <- Var.get[CompositorState]
      _ <- Var.set(s.copy(launcherText = None))
      _ <- Emit.value(ShellEffect.HideLauncher)
    yield ()

  def handleInput(input: LauncherInput)(using Frame): Boolean < (Var[CompositorState] & Emit[ShellEffect]) =
    def updateText(s: CompositorState, newText: String): Boolean < (Var[CompositorState] & Emit[ShellEffect]) =
      for
        _ <- Var.set(s.copy(launcherText = Some(newText)))
        _ <- Emit.value(ShellEffect.UpdateLauncherText(newText))
      yield true

    for
      s <- Var.get[CompositorState]
      result <- s.launcherText match
        case None => false: Boolean < (Var[CompositorState] & Emit[ShellEffect])
        case Some(text) => input match
          case LauncherInput.Character(ch) => updateText(s, text + ch)
          case LauncherInput.Backspace     => updateText(s, text.dropRight(1))
          case LauncherInput.Submit =>
            val spawnEffect: Unit < Emit[ShellEffect] =
              if text.nonEmpty then Emit.value(ShellEffect.SpawnProcess(List("sh", "-c", text)))
              else ((): Unit < Emit[ShellEffect])
            for
              _ <- Var.set(s.copy(launcherText = None))
              _ <- Emit.value(ShellEffect.HideLauncher)
              _ <- spawnEffect
            yield true
          case LauncherInput.Cancel =>
            closeLauncher.andThen(true)
    yield result
