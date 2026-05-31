package core.windows

import core.geometry.Rect

/**
 * Pure model of a compositor window.
 *
 * @param id         stable identity assigned by the shell
 * @param mapped     true once the client has committed its first frame (visible)
 * @param title      window title as reported by the XDG toplevel, if any
 * @param appId      application identifier reported by the XDG toplevel, if any
 * @param geometry   current bounding rectangle in compositor-global coordinates
 * @param floating   true if the window is floating (not tiled)
 * @param fullscreen true if the window is in fullscreen mode
 */
case class WindowState(
  id:         WindowId,
  mapped:     Boolean,
  title:      Option[String],
  appId:      Option[String],
  geometry:   Rect,
  floating:   Boolean = false,
  fullscreen: Boolean = false
)
