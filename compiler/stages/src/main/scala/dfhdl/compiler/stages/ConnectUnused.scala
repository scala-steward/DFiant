package dfhdl.compiler.stages

import dfhdl.compiler.analysis.*
import dfhdl.compiler.ir.*
import dfhdl.compiler.patching.*
import dfhdl.internals.*
import dfhdl.options.CompilerOptions
import scala.collection.mutable

/** This stage connects design instance output ports annotated with @unused to OPEN.
  */
case object ConnectUnused extends Stage:
  def dependencies: List[Stage] = List()
  def nullifies: Set[Stage] = Set()
  def transform(designDB: DB)(using MemberGetSet, CompilerOptions): DB =
    given RefGen = RefGen.fromGetSet
    val patchList: List[(DFMember, Patch)] = designDB.dupPortsByName.view.collect {
      // For design instances
      case (designInst, ports) if !designInst.isTop =>
        val designInstPatches = mutable.ListBuffer.empty[(DFMember, Patch)]
        // Find ports annotated with @unused
        val unusedPorts = ports.view.values.filter { port =>
          port.meta.annotations.exists {
            case _: annotation.Unused => true
            case _                    => false
          }
        }.toList
        // Create connections to OPEN for unused ports
        val dsn = new MetaDesign(designInst, Patch.Add.Config.After):
          for (unusedPort <- unusedPorts) do
            unusedPort.asDclAny <> OPEN
        dsn.patch
    }.toList
    designDB.patch(patchList)
  end transform
end ConnectUnused

extension [T: HasDB](t: T)
  def connectUnused(using CompilerOptions): DB = StageRunner.run(ConnectUnused)(t.db)
