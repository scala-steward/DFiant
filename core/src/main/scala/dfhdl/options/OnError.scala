package dfhdl.options
import dfhdl.internals.{sbtShellIsRunning, sbtTestIsRunning, sbtnIsRunning}

enum OnError derives CanEqual:
  case Exit, Exception
object OnError:
  given OnError = if (sbtShellIsRunning || sbtnIsRunning || sbtTestIsRunning) Exception else Exit
