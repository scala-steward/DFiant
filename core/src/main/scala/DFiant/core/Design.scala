package DFiant.core
import DFiant.internals.*
import DFiant.compiler.ir
import DFiant.compiler.ir.Domain.RT
import DFiant.compiler.ir.Domain.RT.{ClockParams, ResetParams}
import DFiant.compiler.printing.*

private abstract class Design(using DFC) extends Container, HasNamePos:
  private[core] final override lazy val owner: Design.Block =
    Design.Block("???", Position.unknown)
  final protected def setClsNamePos(name: String, position: Position): Unit =
    val designBlock = owner.asIR
    dfc.getSet.replace(designBlock)(
      designBlock.copy(dclName = name, dclPosition = position)
    )

object Design:
  type Block = DFOwner[ir.DFDesignBlock]
  object Block:
    def apply(dclName: String, dclPosition: Position)(using DFC): Block =
      val ownerRef: ir.DFOwner.Ref =
        dfc.ownerOption.map(_.asIR.ref).getOrElse(ir.DFRef.OneWay.Empty)
      ir.DFDesignBlock(
        dclName,
        dclPosition,
        false,
        ownerRef,
        dfc.getMeta,
        ir.DFTags.empty
      ).addMember
        .asFE
  extension [D <: DFDesign](dsn: D) def getDB: ir.DB = dsn.dfc.mutableDB.immutable
//    def printCodeString(): D =
//      import dsn.dfc.getSet
//      given Printer = DefaultPrinter
//      println(getDB.codeString)
//      dsn
end Design

abstract class DFDesign(using DFC) extends Design:
  private[core] type TDmn = Domain.DF
  protected given TDmn = Domain.DF

abstract class RTDesign(
    clkParams: ClockParams,
    rstParams: ResetParams
)(using DFC)
    extends Design:
  private[core] class TDmn extends Domain.RT.HL(clkParams, rstParams)
  protected given TDmn = new TDmn
  lazy val clk = clkParams match
    case RT.NoClock =>
      throw new IllegalArgumentException(
        "Tried to access `clk` but `clkParams` are set to `NoClock`."
      )
    case RT.WithClock(name, _) =>
      DFVal.Dcl(DFBit, IN)(using dfc.setName(name))
  clkParams match
    case _: RT.WithClock => clk
    case _               => // do nothing
  lazy val rst = rstParams match
    case RT.NoReset =>
      throw new IllegalArgumentException(
        "Tried to access `rst` but `rstParams` are set to `NoReset`."
      )
    case RT.WithReset(name, _, _) =>
      DFVal.Dcl(DFBit, IN)(using dfc.setName(name))
  rstParams match
    case _: RT.WithReset => rst
    case _               => // do nothing
end RTDesign

abstract class LLRTDesign(using DFC) extends Design:
  private[core] class TDmn extends Domain.RT.LL
  protected given TDmn = new TDmn
