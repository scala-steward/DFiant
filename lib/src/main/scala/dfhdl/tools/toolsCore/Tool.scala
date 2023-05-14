package dfhdl.tools.toolsCore
import dfhdl.core.Design
import dfhdl.compiler.stages.CommittedDesign

trait Tool:
  final protected def exec[D <: Design](cd: CommittedDesign[D], cmd: String): CommittedDesign[D] =
    import scala.sys.process.*
    Process(cmd).!
    cd

trait Linter extends Tool:
  def lint[D <: Design](cd: CommittedDesign[D]): CommittedDesign[D]
object Linter:
  // default linter will be verilator
  given Linter = dfhdl.tools.linters.verilator