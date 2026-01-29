package dfhdl.compiler.stages

import dfhdl.compiler.analysis.*
import dfhdl.compiler.ir.*
import dfhdl.compiler.patching.*
import dfhdl.options.CompilerOptions
import dfhdl.internals.*
import DFVal.Func.Op as FuncOp
import scala.collection.mutable
//format: off
/** This stage drops all RT waits (and loops depending on them) and replaces them with explicit step `def`s.
 *  rules:
 *  1. Single cycle wait (`1.cy.wait`) is replaced with:
 *     ```scala
 *     def S_N: Step = 
 *       NextStep
 *     end S_N
 *     ```
 *     where N is the step number.
 *  2. Multiple single cycle waits are replaced with sequential step definitions (S_1, S_2, S_3, ...)
 *     ```scala
 *     def S_1: Step =
 *       NextStep
 *     end S_1
 *     def S_2: Step =
 *       NextStep
 *     end S_2
 *     def S_3: Step =
 *       NextStep
 *     end S_3
 *     ```
 *     where 1, 2, 3 are the step numbers.
 *  3. While loop `while (condition) { body }` is replaced with:
 *     ```scala
 *     def S_N: Step = 
 *       if (condition) {
 *         body
 *         ThisStep
 *       } else {
 *         NextStep
 *       }
 *     end S_N
 *     ```
 *  4. While loops with nested waits: waits inside while loops become step definitions with nested naming
 *     (S_1_1, S_1_2, etc.), and the while loop itself becomes a step definition
 */
//format: on
case object DropRTWaits extends Stage:
  def dependencies: List[Stage] = List(DropTimedRTWaits, SimplifyRTOps)
  def nullifies: Set[Stage] = Set()

  def transform(designDB: DB)(using MemberGetSet, CompilerOptions): DB =
    given RefGen = RefGen.fromGetSet
    val patchList = designDB.members.view.collect {
      // each process block has its own step enumeration
      case pb: ProcessBlock if pb.isInRTDomain =>
        val pbMembers = pb.members(MemberView.Flattened)
        // the nested step number is stored in a stack, the head of the list is the current step number
        var stepNumberNest = List(1)
        // for nested while loops, we need to keep track of the exit members.
        // an exit member may have multiple patches that need to be applied in the LIFO order they are stacked.
        var exitMemberPatches = mutable.Map.empty[DFMember, List[Patch]]
        // the step number is incremented by 1
        def nextStepBlock(): Unit =
          stepNumberNest = (stepNumberNest.head + 1) :: stepNumberNest.tail
        // entering a step block starts a deeper nesting level and memoizes an exit member patch that
        // needs to be applied
        def enterStepBlock(patch: (DFMember, Patch)): Unit =
          // stacking the patches for the exit member
          exitMemberPatches.get(patch._1) match
            case Some(patches) => exitMemberPatches += patch._1 -> (patch._2 :: patches)
            case None          => exitMemberPatches += patch._1 -> List(patch._2)
          // starting a new step block with a new step number
          stepNumberNest = 1 :: stepNumberNest
        // checking if the step block has an exit member and returning the patches that need to be applied.
        def checkAndExitStepBlock(lastMember: DFMember): List[(DFMember, Patch)] =
          exitMemberPatches.get(lastMember) match
            case Some(patches) =>
              stepNumberNest = stepNumberNest.tail
              nextStepBlock()
              patches.map(lastMember -> _)
            case None => Nil
        def getStepName(): String =
          stepNumberNest.view.reverse.mkString("S_", "_", "")

        // transforming the process block members.
        // all members except the while loops can be an exit member, and need to be handled with `checkAndExitStepBlock`.
        // waits and while loops are handled specially.
        pbMembers.flatMap {
          // transform a wait statement into a step block (assuming the wait is a single cycle wait, due to previous stages)
          case wait: Wait =>
            val stepName = getStepName()
            nextStepBlock()
            val dsn = new MetaDesign(
              wait,
              Patch.Add.Config.ReplaceWithFirst(Patch.Replace.Config.FullReplacement)
            ):
              import dfhdl.core.StepBlock
              val step = StepBlock.forced(using dfc.setName(stepName))
              dfc.enterOwner(step)
              NextStep
              dfc.exitOwner()
            dsn.patch :: checkAndExitStepBlock(wait)
          // transform a while loop into a step block.
          case wb: DFLoop.DFWhileBlock if !wb.isCombinational =>
            val stepName = getStepName()
            // the last member of the while loop is the exit member.
            val lastLoopMember = wb.getVeryLastMember.get
            // creating the if part of the while loop step block.
            val stepAndIfDsn = new MetaDesign(
              wb,
              Patch.Add.Config.ReplaceWithLast(Patch.Replace.Config.FullReplacement)
            ):
              import dfhdl.core.{StepBlock, DFIf, DFBool, DFUnit}
              val step = StepBlock.forced(using dfc.setName(stepName))
              dfc.enterOwner(step)
              val cond = wb.guardRef.get.asValOf[DFBool]
              val ifBlock = DFIf.Block(Some(cond), DFIf.Header(DFUnit))
              dfc.exitOwner()
            // creating the else part of the while loop step block, to be applied when the while loop exits.
            val elseDsn = new MetaDesign(
              lastLoopMember,
              Patch.Add.Config.After
            ):
              import dfhdl.core.DFIf
              dfc.enterOwner(stepAndIfDsn.ifBlock)
              ThisStep
              dfc.exitOwner()
              val elseBlock = DFIf.Block(None, stepAndIfDsn.ifBlock)
              dfc.enterOwner(elseBlock)
              NextStep
              dfc.exitOwner()
            enterStepBlock(elseDsn.patch)
            Some(stepAndIfDsn.patch)
          case member => checkAndExitStepBlock(member)
        }
    }.flatten.toList

    designDB.patch(patchList)
  end transform
end DropRTWaits

extension [T: HasDB](t: T)
  def dropRTWaits(using CompilerOptions): DB =
    StageRunner.run(DropRTWaits)(t.db)
