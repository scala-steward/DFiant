package dfhdl.options

import BuilderOptions.*

trait BuilderOptions extends ToolOptions:
  val onError: OnError

//defaults common for all linting tools
object BuilderOptions:
  opaque type OnError <: dfhdl.options.ToolOptions.OnError = dfhdl.options.ToolOptions.OnError
  given Conversion[dfhdl.options.OnError, OnError] = x => x.asInstanceOf[OnError]
  object OnError:
    given (using onError: dfhdl.options.ToolOptions.OnError): OnError = onError
    export dfhdl.options.OnError.*
