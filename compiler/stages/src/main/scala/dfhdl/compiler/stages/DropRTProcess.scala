package dfhdl.compiler.stages

import dfhdl.compiler.analysis.*
import dfhdl.compiler.ir.*
import dfhdl.compiler.patching.*
import dfhdl.options.CompilerOptions
import dfhdl.internals.*
import DFVal.Func.Op as FuncOp
import scala.collection.mutable
import scala.collection.immutable.ListMap
import dfhdl.core.DFType.asFE
import dfhdl.core.{DFValAny, DFOwnerAny, asValAny, DFC}
//format: off
/** This stage drops RT processes by creating an explicit enumerated state machines
  */
//format: on
case object DropRTProcess extends Stage:
  def dependencies: List[Stage] = List() // DropRTWaits
  def nullifies: Set[Stage] = Set(DFHDLUniqueNames)

  def transform(designDB: DB)(using MemberGetSet, CompilerOptions): DB =
    given RefGen = RefGen.fromGetSet
    val patchList = designDB.members.view.collect {
      case pb: ProcessBlock if pb.isInRTDomain =>
        val stateBlocks = pb.members(MemberView.Folded).collect {
          case sb: StepBlock if sb.isRegular =>
            sb
        }
        val gotos = pb.members(MemberView.Flattened).collect { case g: Goto => g }
        val pbPatch = pb -> Patch.Replace(pb.getOwner, Patch.Replace.Config.ChangeRefAndRemove)
        if (stateBlocks.isEmpty) List(pbPatch)
        else
          // assuming flat step blocks structure
          val nextBlocks = stateBlocks.lazyZip(stateBlocks.tail :+ stateBlocks.head).toMap
          val enumName = if (pb.isAnonymous) s"State" else s"${pb.getName}_State"
          val stateRegName = if (pb.isAnonymous) s"state" else s"${pb.getName}_state"
          val entries = ListMap.from(stateBlocks.view.zipWithIndex.map { case (sb, idx) =>
            sb.getName -> BigInt(idx)
          })
          val stateEnumIR = DFEnum(enumName, stateBlocks.length.bitsWidth(false), entries)
          type StateEnum = dfhdl.core.DFEnum[dfhdl.core.DFEncoding]
          val stateEnumFE = stateEnumIR.asFE[StateEnum]
          def enumEntry(value: BigInt)(using DFC) = dfhdl.core.DFVal.Const(stateEnumFE, Some(value))
          val dsn = new MetaDesign(
            pb,
            Patch.Add.Config.Before,
            dfhdl.core.DomainType.RT(dfhdl.core.RTDomainCfg.Derived)
          ):
            val stateInit = dfhdl.core.DFVal.Const(stateEnumFE, Some(entries.head._2))
            val patterns = entries.map { case (_, value) =>
              dfhdl.core.DFMatch.Pattern.Singleton(enumEntry(value))
            }
            val stateReg = stateEnumFE.<>(VAR.REG).init(stateInit)(using dfc.setName(stateRegName))
            val header = dfhdl.core.DFMatch.Header(dfhdl.core.DFUnit, stateReg).asIR
          val stateReg = dsn.stateReg
          var prevBlockOrHeader: DFOwnerAny | DFValAny = dsn.header.asValAny
          val caseDsns = stateBlocks.lazyZip(dsn.patterns).map { (sb, pattern) =>
            new MetaDesign(
              sb,
              Patch.Add.Config.ReplaceWithLast(Patch.Replace.Config.ChangeRefAndRemove),
              dfhdl.core.DomainType.RT(dfhdl.core.RTDomainCfg.Derived)
            ):
              val block = dfhdl.core.DFMatch.Block(pattern, None, prevBlockOrHeader)(using
                dfc.setMeta(sb.meta)
              )
              prevBlockOrHeader = block
          }
          val removedOnEntryExitFallThroughMembers = mutable.Set.empty[DFMember]
          val gotoDsns = gotos.map { g =>
            new MetaDesign(
              g,
              Patch.Add.Config.ReplaceWithLast(Patch.Replace.Config.ChangeRefAndRemove),
              dfhdl.core.DomainType.RT(dfhdl.core.RTDomainCfg.Derived)
            ):
              import dfhdl.core.{DFIf, DFUnit, DFBool}
              val currentStepBlock = g.getOwnerStepBlock
              val nextStepBlock: StepBlock = g.stepRef.get match
                case stepBlock: StepBlock => stepBlock
                case Goto.ThisStep        => currentStepBlock
                case Goto.NextStep        => nextBlocks(currentStepBlock)
                case Goto.FirstStep       => stateBlocks.head
              def setState(nextStepBlock: StepBlock): Unit =
                stateReg.din.:=(enumEntry(entries(nextStepBlock.getName)))(using
                  dfc.setMeta(g.meta)
                )
              if (currentStepBlock != nextStepBlock)
                // add currentStepBlock-onExit members
                currentStepBlock.members(MemberView.Folded).collectFirst {
                  case onExit: StepBlock if onExit.isOnExit => onExit
                }.foreach { onExit =>
                  val onExitMembers = onExit.members(MemberView.Flattened)
                  plantClonedMembers(onExit, onExitMembers)
                  removedOnEntryExitFallThroughMembers += onExit
                  removedOnEntryExitFallThroughMembers ++= onExitMembers
                }
                def handleNextStep(nextStepBlock: StepBlock): Unit =
                  // onEntry members will always activated, even if we fall-through the next step block
                  val nextStepBlockMembers = nextStepBlock.members(MemberView.Flattened)
                  nextStepBlockMembers.collectFirst {
                    case onEntry: StepBlock if onEntry.isOnEntry => onEntry
                  }.foreach { onEntry =>
                    val onEntryMembers = onEntry.members(MemberView.Flattened)
                    plantClonedMembers(onEntry, onEntryMembers)
                    removedOnEntryExitFallThroughMembers += onEntry
                    removedOnEntryExitFallThroughMembers ++= onEntryMembers
                  }
                  setState(nextStepBlock)
                  // in case of circular fall-through, we stop
                  if (nextStepBlock != currentStepBlock)
                    val fallThroughCond = nextStepBlockMembers.collectFirst {
                      case fallThrough: StepBlock if fallThrough.isFallThrough =>
                        val fallThroughMembers = fallThrough.members(MemberView.Flattened)
                        val fallThroughDFC = dfc.setMeta(fallThrough.meta.anonymize)
                        removedOnEntryExitFallThroughMembers += fallThrough
                        removedOnEntryExitFallThroughMembers ++= fallThroughMembers
                        // planting all members except the last one (the ident of the condition)
                        val clonedMemberMap =
                          plantClonedMembers(fallThrough, fallThroughMembers.dropRight(1))
                        val Ident(origCond) = fallThroughMembers.last.runtimeChecked
                        val cond = clonedMemberMap.getOrElse(origCond, origCond).asInstanceOf[DFVal]
                        val ifBlock = DFIf.Block(
                          Some(cond.asValOf[DFBool]),
                          DFIf.Header(DFUnit)(using fallThroughDFC)
                        )(using fallThroughDFC)
                        dfc.enterOwner(ifBlock)
                        val fallThroughStepBlock = nextBlocks(nextStepBlock)
                        handleNextStep(fallThroughStepBlock)
                        dfc.exitOwner()
                    }
                  end if
                end handleNextStep
                handleNextStep(nextStepBlock)
              else
                setState(nextStepBlock)
              end if
          }
          Iterator(
            List(dsn.patch, pbPatch),
            removedOnEntryExitFallThroughMembers.map(_ -> Patch.Remove()),
            caseDsns.map(_.patch),
            gotoDsns.map(_.patch)
          ).flatten
        end if
    }.flatten.toList

    designDB.patch(patchList)
  end transform
end DropRTProcess

extension [T: HasDB](t: T)
  def dropRTProcess(using CompilerOptions): DB =
    StageRunner.run(DropRTProcess)(t.db)
