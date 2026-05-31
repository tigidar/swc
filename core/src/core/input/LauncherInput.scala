package core.input

/** Input events for the launcher text field. */
enum LauncherInput:
  case Character(ch: Char)
  case Backspace
  case Submit   // Enter
  case Cancel   // Escape
