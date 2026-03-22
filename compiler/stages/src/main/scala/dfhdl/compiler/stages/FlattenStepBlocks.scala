package dfhdl.compiler.stages

import dfhdl.compiler.analysis.*
import dfhdl.compiler.ir.*
import dfhdl.compiler.patching.*
import dfhdl.options.CompilerOptions
import dfhdl.internals.*
import scala.annotation.tailrec
//format: off
/** This stage flattens the nested StepBlock hierarchy produced by [[DropRTWaits]] so that every
  * StepBlock in an RT process becomes a direct child of the enclosing ProcessBlock, and resolves
  * every relative `Goto` reference (`NextStep`, `ThisStep`, `FirstStep`) to an explicit reference
  * to the concrete target StepBlock. After this stage, every `Goto` in every RT process carries
  * an explicit `StepBlock` reference — no relative form remains.
  *
  * == Background ==
  *
  * After [[DropRTWaits]], waits and while-loops inside an RT process have been converted into
  * StepBlocks. When the original source contained nested wait/while constructs (e.g. a while loop
  * with waits inside it, or a user-defined step block that contains further step blocks), the
  * resulting IR reflects that nesting: some StepBlocks own other StepBlocks as children.
  * Each step's control-flow terminus is a `Goto` member whose `stepRef` points to one of:
  *   - A concrete `StepBlock` — an explicit jump to that state (already resolved).
  *   - `Goto.NextStep` — advance to the "next" state in the sequential order (relative).
  *   - `Goto.ThisStep` — loop back to the current state (relative; used by while-loop steps).
  *   - `Goto.FirstStep` — jump to the first state of the process (relative).
  *
  * The three relative forms must all be resolved to explicit `StepBlock` references before
  * [[DropRTProcess]] can generate the FSM. `NextStep` additionally requires hierarchy context
  * that is destroyed by flattening, so all three are resolved here.
  *
  * == Transformation Rules ==
  *
  * 1. Relative `NextStep` in the last step of a process (in DFS pre-order) wraps around to the
  *    first step. In all other steps `NextStep` advances to the immediately following step:
  *    ```scala
  *    // input
  *    process:
  *      def S0: Step =
  *        y.din := 0
  *        NextStep
  *      end S0
  *      def S1: Step =
  *        y.din := 1
  *        NextStep
  *      end S1
  *    // output
  *    process:
  *      def S0: Step =
  *        y.din := 0
  *        S1
  *      end S0
  *      def S1: Step =
  *        y.din := 1
  *        S0
  *      end S1
  *    ```
  * 2. `ThisStep` resolves to the enclosing step; `FirstStep` resolves to the first step of the
  *    process (DFS pre-order head):
  *    ```scala
  *    // input — S_0 loops to itself; S_1 jumps back to S_0
  *    process:
  *      def S_0: Step =
  *        if (i) ThisStep else NextStep
  *      end S_0
  *      def S_1: Step =
  *        if (i) FirstStep else NextStep
  *      end S_1
  *      def S_2: Step = NextStep
  *      end S_2
  *    // output
  *    process:
  *      def S_0: Step =
  *        if (i) S_0 else S_1
  *      end S_0
  *      def S_1: Step =
  *        if (i) S_0 else S_2
  *      end S_1
  *      def S_2: Step = S_0
  *      end S_2
  *    ```
  * 3. Non-step statements that appear between consecutive steps at any nesting level are relocated
  *    into the body of the immediately preceding step (before its terminal `NextStep` goto).
  *    They are placed at the end of the deepest last-child step, so they execute just before
  *    control leaves that sub-tree:
  *    ```scala
  *    // input
  *    process:
  *      def S_0: Step = NextStep
  *      end S_0
  *      x.din := i        // inter-step statement
  *      def S_1: Step = NextStep
  *      end S_1
  *    // output — x.din := i moved into S_0 before its goto
  *    process:
  *      def S_0: Step =
  *        x.din := i
  *        S_1
  *      end S_0
  *      def S_1: Step = S_0
  *      end S_1
  *    ```
  * 4. Nested StepBlocks (a step that directly contains another step) are lifted one level at a
  *    time until all steps are direct children of the ProcessBlock. The parent step's `NextStep`
  *    is replaced by a goto to the first child step; the last child's `NextStep` becomes the
  *    former parent's `NextStep` target:
  *    ```scala
  *    // input
  *    process:
  *      def MyStep: Step =
  *        def MyStep_0: Step = NextStep
  *        end MyStep_0
  *        NextStep
  *      end MyStep
  *    // output
  *    process:
  *      def MyStep: Step = MyStep_0
  *      end MyStep
  *      def MyStep_0: Step = MyStep
  *      end MyStep_0
  *    ```
  * 5. A StepBlock nested directly inside a conditional branch is extracted to ProcessBlock level.
  *    A goto to that step replaces it in the branch; the "consumed Goto" that immediately followed
  *    it in the branch (encoding what happens when the step sequence ends) is removed and its
  *    target becomes the extracted step's terminal goto:
  *    ```scala
  *    // input
  *    process:
  *      def S_0: Step =
  *        if (i)
  *          def S_0_0: Step = NextStep
  *          end S_0_0
  *          ThisStep        // consumed goto: S_0_0's next is S_0
  *        else
  *          NextStep        // else branch: S_0's next is S_1
  *        end if
  *      end S_0
  *      def S_1: Step = NextStep
  *      end S_1
  *    // output
  *    process:
  *      def S_0: Step =
  *        if (i) S_0_0
  *        else S_1
  *      end S_0
  *      def S_0_0: Step = S_0   // NextStep of S_0_0 resolved via consumed ThisStep -> S_0
  *      end S_0_0
  *      def S_1: Step = S_0
  *      end S_1
  *    ```
  *
  * == Implementation Phases ==
  *
  * The stage applies four sequential `db.patch()` calls to avoid patch conflicts:
  *
  * - **Phase 0** (inter-step relocation): moves trailing statements before the `NextStep` Goto of
  *   `deepestLastChild(stepI)` — processed inner-first so Move patches concatenate correctly.
  * - **Phase 1** (conditional extraction): uses the Phase-0 DB so relocated statements travel with
  *   the extracted step. Inserts a goto at the branch site, removes the consumed Goto, moves step
  *   and all descendants to ProcessBlock level.
  * - **Phase 2** (structural flattening): one level per `@tailrec` pass — each pass moves direct
  *   nested children (with full `Flattened` descendants) up one level.
  * - **Phase 3** (goto resolution): `ChangeRef` patches computed from the *original* DB, so
  *   `nextStepMap` and `conditionalStepMap` remain correct regardless of structural changes made
  *   in Phases 0–2.
  */
//format: on
case object FlattenStepBlocks extends Stage:
  def dependencies: List[Stage] = List(DropRTWaits)
  def nullifies: Set[Stage] = Set()

  def transform(designDB: DB)(using MemberGetSet, CompilerOptions): DB =
    given RefGen = RefGen.fromGetSet
    // Phase 3 ChangeRef patches are computed from the original DB.
    val gotoPatchList = designDB.members.view.flatMap {
      case pb: ProcessBlock if pb.isInRTDomain => collectGotoPatches(pb)
      case _                                    => Nil
    }.toList
    // Phase 0: inter-step relocation (Step 5 inter-step + Step 6)
    val db0 = designDB.patch(
      designDB.members.view.flatMap {
        case pb: ProcessBlock if pb.isInRTDomain => collectInterStepPatches(pb)
        case _                                    => Nil
      }.toList
    )
    // Phase 1: conditional branch extraction (uses db0 for updated member structure)
    val db1 = locally {
      given MemberGetSet = db0.getSet
      db0.patch(
        db0.members.view.flatMap {
          case pb: ProcessBlock if pb.isInRTDomain => collectConditionalExtractionPatches(pb)
          case _                                    => Nil
        }.toList
      )
    }
    // Phase 2: structural flattening, one level at a time (uses db1, applied repeatedly)
    val db2 = flattenRepeatedly(db1)
    // Phase 3: Goto ChangeRef
    db2.patch(gotoPatchList)
  end transform

  // Repeatedly flatten one nesting level of StepBlocks until all are direct pb children.
  @tailrec private def flattenRepeatedly(db: DB)(using RefGen): DB =
    given MemberGetSet = db.getSet
    val patches = db.members.view.flatMap {
      case pb: ProcessBlock if pb.isInRTDomain => collectFlattenPatchesOneLevel(pb)
      case _                                    => Nil
    }.toList
    if patches.isEmpty then db
    else flattenRepeatedly(db.patch(patches))

  // --- Shared helpers ---

  private def collectDirectFlatSteps(owner: DFOwner)(using MemberGetSet): List[StepBlock] =
    owner.members(MemberView.Folded).flatMap {
      case sb: StepBlock if sb.isRegular => sb :: collectDirectFlatSteps(sb)
      case _                             => Nil
    }

  private def findConsumedGoto(s: StepBlock)(using MemberGetSet): (DFConditional.Block, Goto) =
    val cb = s.getOwner.asInstanceOf[DFConditional.Block]
    val cbMembers = cb.members(MemberView.Folded)
    val sIdx = cbMembers.indexOf(s)
    val consumedGoto = cbMembers.drop(sIdx + 1).collectFirst { case g: Goto => g }.get
    (cb, consumedGoto)

  private def deepestLastChild(step: StepBlock)(using MemberGetSet): StepBlock =
    step.members(MemberView.Folded)
      .collect { case sb: StepBlock if sb.isRegular => sb }
      .lastOption match
      case None       => step
      case Some(last) => deepestLastChild(last)

  private def findNextStepGoto(step: StepBlock)(using MemberGetSet): Option[Goto] =
    step.members(MemberView.Flattened).collectFirst {
      case g: Goto if g.stepRef.get == Goto.NextStep => g
    }

  // --- Phase 3: Goto ChangeRef patches (computed from original DB) ---

  private def collectGotoPatches(pb: ProcessBlock)(using MemberGetSet): List[(DFMember, Patch)] =
    val flatSteps = collectDirectFlatSteps(pb)
    if flatSteps.isEmpty then return Nil
    val nextStepMap = (flatSteps lazyZip (flatSteps.tail :+ flatSteps.head)).toMap
    val firstStep = flatSteps.head
    val conditionalBranchSteps = pb.members(MemberView.Flattened).collect {
      case sb: StepBlock if sb.isRegular && sb.getOwner.isInstanceOf[DFConditional.Block] => sb
    }
    val consumedGotos = conditionalBranchSteps.map(findConsumedGoto(_)._2).toSet
    val conditionalStepMap = conditionalBranchSteps.map { s =>
      val (_, consumedGoto) = findConsumedGoto(s)
      val target: StepBlock = consumedGoto.stepRef.get match
        case sb: StepBlock  => sb
        case Goto.ThisStep  => consumedGoto.getOwnerStepBlock
        case Goto.NextStep  => nextStepMap(consumedGoto.getOwnerStepBlock)
        case Goto.FirstStep => firstStep
      s -> target
    }.toMap
    pb.members(MemberView.Flattened)
      .collect { case g: Goto if !consumedGotos.contains(g) => g }
      .flatMap { g =>
        g.stepRef.get match
          case _: StepBlock => None
          case Goto.ThisStep =>
            Some(g -> Patch.ChangeRef(_.asInstanceOf[Goto].stepRef, g.getOwnerStepBlock))
          case Goto.FirstStep =>
            Some(g -> Patch.ChangeRef(_.asInstanceOf[Goto].stepRef, firstStep))
          case Goto.NextStep =>
            val owningStep = g.getOwnerStepBlock
            val target = conditionalStepMap.getOrElse(owningStep, nextStepMap(owningStep))
            Some(g -> Patch.ChangeRef(_.asInstanceOf[Goto].stepRef, target))
      }
  end collectGotoPatches

  // --- Phase 0: Inter-step relocation patches ---

  private def collectInterStepPatches(
      pb: ProcessBlock
  )(using MemberGetSet): List[(DFMember, Patch)] =
    val flatSteps = collectDirectFlatSteps(pb)
    if flatSteps.isEmpty then return Nil
    // Step 5 inter-step: relocate statements in conditional branches into the step's body
    val conditionalBranchSteps = pb.members(MemberView.Flattened).collect {
      case sb: StepBlock if sb.isRegular && sb.getOwner.isInstanceOf[DFConditional.Block] => sb
    }
    val step5InterStep = conditionalBranchSteps.flatMap { s =>
      val (cb, consumedGoto) = findConsumedGoto(s)
      val cbMembers = cb.members(MemberView.Folded)
      val sIdx = cbMembers.indexOf(s)
      val consumedGotoIdx = cbMembers.indexOf(consumedGoto)
      val interStepStmts = cbMembers.slice(sIdx + 1, consumedGotoIdx)
        .filterNot(m => m.isInstanceOf[StepBlock] || m.isInstanceOf[Goto])
      val targetStep = deepestLastChild(s)
      findNextStepGoto(targetStep).toList.flatMap { nextStepGoto =>
        interStepStmts.map { stmt =>
          nextStepGoto -> Patch.Move(List(stmt), stmt.getOwner, Patch.Move.Config.Before)
        }
      }
    }
    // Step 6: relocate inter-step statements at each nesting level (inner-first for correct order)
    def collectOwners(owner: DFOwner): List[DFOwner] =
      owner.members(MemberView.Folded)
        .collect { case sb: StepBlock if sb.isRegular => sb }
        .flatMap(collectOwners) :+ owner
    val step6 = collectOwners(pb).flatMap { owner =>
      val directMembers = owner.members(MemberView.Folded)
      val directSteps = directMembers.collect { case sb: StepBlock if sb.isRegular => sb }
      if directSteps.isEmpty then Nil
      else
        directSteps.zipWithIndex.flatMap { (step, idx) =>
          val stepPos = directMembers.indexOf(step)
          val nextPos =
            if idx + 1 < directSteps.length then directMembers.indexOf(directSteps(idx + 1))
            else directMembers.length
          val stmtsToMove = directMembers.slice(stepPos + 1, nextPos).filterNot {
            case _: StepBlock => true
            case _: Goto      => true
            case _            => false
          }
          if stmtsToMove.isEmpty then Nil
          else
            val targetStep = deepestLastChild(step)
            findNextStepGoto(targetStep).toList.flatMap { nextStepGoto =>
              stmtsToMove.map { stmt =>
                nextStepGoto -> Patch.Move(List(stmt), stmt.getOwner, Patch.Move.Config.Before)
              }
            }
        }
    }
    step5InterStep ++ step6
  end collectInterStepPatches

  // --- Phase 1: Conditional branch extraction (uses db0's member structure) ---

  private def collectConditionalExtractionPatches(
      pb: ProcessBlock
  )(using MemberGetSet, RefGen): List[(DFMember, Patch)] =
    val flatSteps = collectDirectFlatSteps(pb)
    if flatSteps.isEmpty then return Nil
    val conditionalBranchSteps = pb.members(MemberView.Flattened).collect {
      case sb: StepBlock if sb.isRegular && sb.getOwner.isInstanceOf[DFConditional.Block] => sb
    }
    conditionalBranchSteps.flatMap { s =>
      val (cb, consumedGoto) = findConsumedGoto(s)
      // Insert an explicit Goto to s at s's former position in the branch
      val dsn = new MetaDesign(s, Patch.Add.Config.Before):
        import dfhdl.core.*
        Goto(s.refTW[Goto], dfc.ownerOrEmptyRef, dfc.getMeta, dfc.tags).addMember
      // Remove the consumed goto
      val removeConsumedGoto: (DFMember, Patch) = consumedGoto -> Patch.Remove()
      // Move s and ALL its descendants to after the parent step at pb level.
      // Including descendants ensures the flat member list maintains valid ownership ordering.
      val parentStep = cb.getOwnerStepBlock
      val allMembersToMove = s :: s.members(MemberView.Flattened)
      val movePatch: (DFMember, Patch) =
        parentStep -> Patch.Move(allMembersToMove, cb, Patch.Move.Config.After)
      List(dsn.patch, removeConsumedGoto, movePatch)
    }
  end collectConditionalExtractionPatches

  // --- Phase 2: One-level structural flattening ---

  private def collectFlattenPatchesOneLevel(
      pb: ProcessBlock
  )(using MemberGetSet): List[(DFMember, Patch)] =
    // For each direct pb-child step, lift its immediate nested StepBlock children one level up.
    // Each lift moves the child and ALL its descendants so the flat member list stays valid.
    // Multiple levels require repeated application (see flattenRepeatedly).
    pb.members(MemberView.Folded).flatMap {
      case topAncestor: StepBlock if topAncestor.isRegular =>
        topAncestor.members(MemberView.Folded).flatMap {
          case child: StepBlock if child.isRegular =>
            val allMembersToMove = child :: child.members(MemberView.Flattened)
            List(
              topAncestor -> Patch.Move(allMembersToMove, child.getOwner, Patch.Move.Config.After)
            )
          case _ => Nil
        }
      case _ => Nil
    }
  end collectFlattenPatchesOneLevel
end FlattenStepBlocks

extension [T: HasDB](t: T)
  def flattenStepBlocks(using CompilerOptions): DB =
    StageRunner.run(FlattenStepBlocks)(t.db)
