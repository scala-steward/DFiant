package dfhdl.core
import dfhdl.internals.*
import dfhdl.compiler.ir
import dfhdl.compiler.printing.*

private[dfhdl] abstract class Design(using DFC) extends Container, HasNamePos:
  private[core] type TScope = DFC.Scope.Design
  final protected given TScope = DFC.Scope.Design
  private[core] final override lazy val owner: Design.Block =
    Design.Block(__domainType, "???", Position.unknown)
  final protected def setClsNamePos(name: String, position: Position): Unit =
    val designBlock = owner.asIR
    dfc.getSet.replace(designBlock)(
      designBlock.copy(dclName = name, dclPosition = position)
    )
  final override def onCreateStartLate: Unit =
    dfc.enterLate()

object Design:
  type Block = DFOwner[ir.DFDesignBlock]
  object Block:
    def apply(domain: ir.DomainType, dclName: String, dclPosition: Position)(using DFC): Block =
      val ownerRef: ir.DFOwner.Ref =
        dfc.ownerOption.map(_.asIR.ref).getOrElse(ir.DFRef.OneWay.Empty)
      ir.DFDesignBlock(domain, dclName, dclPosition, false, ownerRef, dfc.getMeta, ir.DFTags.empty)
        .addMember
        .asFE
  extension [D <: Design](dsn: D) def getDB: ir.DB = dsn.dfc.mutableDB.immutable
end Design

abstract class DFDesign(using DFC) extends Design:
  private[core] type TDomain = DFC.Domain.DF
  final protected given TDomain = DFC.Domain.DF
  final private[core] lazy val __domainType: ir.DomainType = ir.DomainType.DF

abstract class RTDesign(
    cfg: ir.RTDomainCfg = ir.DerivedCfg
)(using DFC)
    extends Design:
  private[core] type TDomain = DFC.Domain.RT
  final protected given TDomain = DFC.Domain.RT
  final private[core] lazy val __domainType: ir.DomainType = ir.DomainType.RT(cfg)

  //  /** This is a reference to the clock used. `clkCfg` must be explicitly defined with a name before
//    * using this value.
//    */
//  final lazy val clk = clkCfg match
//    case ClkCfg.Explicit(name: String, _) =>
//      DFVal.Dcl(DFBit, Modifier.IN)(using dfc.setName(name))
//    case _ =>
//      throw new IllegalArgumentException(
//        "Tried to access `clk` but `clkCfg` has no explicit clock name."
//      )
//  // forcing the clock to be added if the name is explicitly defined
//  clkCfg match
//    case ClkCfg.Explicit(_: String, _) => clk // touching lazy value
//    case _                             => // do nothing
//  /** This is a reference to the reset used. `rstCfg` must be explicitly defined with a name before
//    * using this value.
//    */
//  lazy val rst = rstCfg match
//    case RstCfg.Explicit(name: String, _, _) =>
//      DFVal.Dcl(DFBit, Modifier.IN)(using dfc.setName(name))
//    case _ =>
//      throw new IllegalArgumentException(
//        "Tried to access `rst` but `rstCfg` has no explicit reset name."
//      )
//  // forcing the reset to be added if the name is explicitly defined
//  rstCfg match
//    case RstCfg.Explicit(_: String, _, _) => rst
//    case _                                => // do nothing
end RTDesign

abstract class EDDesign(using DFC) extends Design:
  private[core] type TDomain = DFC.Domain.ED
  final protected given TDomain = DFC.Domain.ED
  final private[core] lazy val __domainType: ir.DomainType = ir.DomainType.ED