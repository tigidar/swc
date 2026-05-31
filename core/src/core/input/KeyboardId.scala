package core.input

/**
 * Opaque identifier for a keyboard input device.
 *
 * The compositor shell constructs a `KeyboardId` by converting a
 * `Ptr[WlrKeyboard]` address to a hex string via `ptr.toLong.toHexString`,
 * keeping the pure core free of FFI types while preserving device identity.
 */
opaque type KeyboardId = String

object KeyboardId:
  def apply(s: String): KeyboardId       = s
  def value(id: KeyboardId): String      = id
