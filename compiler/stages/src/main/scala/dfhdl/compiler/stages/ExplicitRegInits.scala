package dfhdl.compiler.stages

import dfhdl.compiler.analysis.*
import dfhdl.compiler.ir.*
import dfhdl.compiler.patching.*
import dfhdl.options.CompilerOptions
import DFVal.Alias.History.Op as HistoryOp
import dfhdl.core.Domain
import Patch.Add.Config as AddCfg
import Patch.Replace.Config as ReplaceCfg
import collection.mutable

/** This stage propagates initialization values to the reg alias init value, and removes all
  * declaration initializations.
  */
case object ExplicitRegInits extends Stage:
  def dependencies: List[Stage] = List()
  def nullifies: Set[Stage] = Set(DropUnreferencedAnons)
  def transform(designDB: DB)(using MemberGetSet, CompilerOptions): DB =
    val patchList = designDB.members.collect {
      case dcl: DFVal.Dcl
          if dcl.initRefList.nonEmpty && !dcl.modifier.isReg && dcl.isRTDomain && !dcl.isConstVAR =>
        dcl -> Patch.Replace(dcl.copy(initRefList = Nil), Patch.Replace.Config.FullReplacement)
      case ra @ DFVal.Alias.History(_, DFRef(dcl: DFVal.Dcl), _, HistoryOp.State, None, _, _, _)
          if ra.isRTDomain =>
        // patch to add an init from the Dcl onto the register construct
        new MetaDesign(ra, AddCfg.ReplaceWithLast(ReplaceCfg.FullReplacement)):
          val clonedInit = dcl.initList.head.cloneAnonValueAndDepsHere.asConstAny
          dfhdl.core.DFVal.Alias.History(
            dcl.asValAny,
            ra.step,
            HistoryOp.State,
            Some(clonedInit)
          )(using dfc.setMeta(ra.meta))
        .patch
    }
    designDB.patch(patchList)
  end transform
end ExplicitRegInits

extension [T: HasDB](t: T)
  def explicitRegInits(using CompilerOptions): DB =
    StageRunner.run(ExplicitRegInits)(t.db)
