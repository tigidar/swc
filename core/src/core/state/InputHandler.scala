package core.state

import kyo.*
import core.input.*
import core.windows.WindowId

/**
 * Pure `InputEvent` dispatcher.
 *
 * The compositor shell extracts primitives from wlroots, constructs an
 * `InputEvent`, and passes it to [[handle]] via `EventHandler.run`.
 * `handle` records activity (idle/screen-off wake) then routes the event to
 * the sibling domain handler that owns it — [[KeyboardHandler]],
 * [[PointerHandler]], [[WindowHandler]], [[OutputHandler]], [[WindowActions]],
 * and the [[EventHandler]] grab setters — each of which updates
 * `CompositorState` and emits the `ShellEffect`s the shell must perform on
 * wlroots. Keeping the routing here and the domain logic in cohesive siblings
 * keeps every callback pure and JVM-testable.
 */
object InputHandler:

  type Effects = EventHandler.Effects

  /** Dispatch an `InputEvent` to its handler. */
  def handle(event: InputEvent)(using Frame): Unit < Effects =
    for
      _ <- recordActivity(event)
      _ <- dispatch(event)
    yield ()

  /**
   * Reset the idle timer for genuine user input (keyboard / pointer).
   *
   * Window and output lifecycle events are not user activity and must not
   * keep the session awake, otherwise a chatty client could prevent
   * auto-lock. Uses the event's `time_msec` so it shares the clock that
   * [[IdleHandler.checkIdle]] compares against.
   */
  private def recordActivity(event: InputEvent)(using Frame): Unit < Effects =
    val activityMs: Option[Long] = event match
      case e: InputEvent.KeyPress      => Some(e.time)
      case e: InputEvent.KeyRelease    => Some(e.time)
      case e: InputEvent.PointerMoved  => Some(e.time)
      case e: InputEvent.PointerButton => Some(e.time)
      case _                           => None
    activityMs match
      case Some(ms) =>
        for
          s <- Var.get[CompositorState]
          // Real input also wakes the screen: if the screen-off idle timeout
          // had powered the outputs off, clear the flag and emit the power-on
          // effect so the shell re-enables them.
          _ <- Var.set(s.copy(lastActivityMs = ms, screenOff = false))
          _ <- if s.screenOff then Emit.value(ShellEffect.SetScreenOff(false))
               else ((): Unit < Effects)
        yield ()
      case None => ((): Unit < Effects)

  private def dispatch(event: InputEvent)(using Frame): Unit < Effects =
    event match
      // Output lifecycle — delegate to EventHandler
      case InputEvent.OutputAdded(id, width, height, layoutX, layoutY) =>
        OutputHandler.addOutput(id, width, height, layoutX, layoutY)
      case InputEvent.OutputRemoved(id)       => OutputHandler.removeOutput(id)
      case InputEvent.UsableAreaChanged(id, area) =>
        OutputHandler.updateUsableArea(id, area)

      // Keyboard device lifecycle — delegate to KeyboardHandler
      case InputEvent.KeyboardAdded(id)     => KeyboardHandler.added(id)
      case InputEvent.KeyboardRemoved(id)   => KeyboardHandler.removed(id)

      // Key events — delegate to KeyboardHandler
      case e: InputEvent.KeyPress           => KeyboardHandler.keyPress(e)
      case e: InputEvent.KeyRelease         => KeyboardHandler.keyRelease(e)

      // Window lifecycle — delegate to WindowHandler
      case _: InputEvent.WindowCreated      => ((): Unit < Effects)
      case InputEvent.WindowMapped(id, oid, title, appId) =>
        WindowHandler.mapWindow(id, oid, title, appId)
      case InputEvent.WindowUnmapped(id)    => WindowHandler.unmapWindow(id)
      case InputEvent.WindowDestroyed(id)   => WindowHandler.destroyWindow(id)
      case InputEvent.RequestMove(id, cx, cy, wx, wy) =>
        EventHandler.beginGrab(GrabState.Moving(id, cx, cy, wx, wy))
      case InputEvent.RequestResize(id, edges, cx, cy, wx, wy, ww, wh) =>
        EventHandler.beginGrab(GrabState.Resizing(id, cx, cy, wx, wy, ww, wh, edges))
      case _: InputEvent.RequestFullscreen  => WindowActions.toggleFullscreen

      // Pointer events — delegate to PointerHandler
      case e: InputEvent.PointerMoved       => PointerHandler.moved(e)
      case e: InputEvent.PointerButton      => PointerHandler.button(e)

  /** Allocate a new `WindowId`. Delegates to `WindowHandler.createWindow`. */
  def handleNewWindow(title: Option[String], appId: Option[String])(using Frame): WindowId < Effects =
    WindowHandler.createWindow(title, appId)
