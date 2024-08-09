package dfhdl.compiler.stages.vhdl
import dfhdl.compiler.printing.*
import dfhdl.compiler.ir.*
import dfhdl.compiler.analysis.*
import dfhdl.internals.*
import DFVal.*
import dfhdl.compiler.ir.ProcessBlock.Sensitivity
import dfhdl.compiler.ir.DFConditional.DFCaseBlock.Pattern
import scala.collection.mutable
import scala.collection.immutable.ListSet
protected trait VHDLOwnerPrinter extends AbstractOwnerPrinter:
  type TPrinter <: VHDLPrinter
  val useStdSimLibrary: Boolean = true
  def fileSuffix = "vhdl"
  def packageName: String =
    s"${getSet.designDB.top.dclName}_pkg"
  def csLibrary(inSimulation: Boolean): String =
    val default =
      s"""library ieee;
         |use ieee.std_logic_1164.all;
         |use ieee.numeric_std.all;
         |use work.$packageName.all;""".stripMargin
    if (useStdSimLibrary && inSimulation)
      s"""$default
         |
         |library std;
         |use std.env.all;""".stripMargin
    else default
  def entityName(design: DFDesignBlock): String = design.dclName
  def csEntityDcl(design: DFDesignBlock): String =
    val designMembers = design.members(MemberView.Folded)
    val ports = designMembers.view
      .collect { case p @ DclPort() =>
        printer.csDFMember(p)
      }
      .mkString(";\n")
    val designParamList = designMembers.collect { case param @ DesignParam(_) =>
      val defaultValue = if (design.isTop) s" := ${param.relValRef.refCodeString}" else ""
      s"${param.getName} : ${printer.csDFType(param.dfType)}$defaultValue"
    }
    val genericBlock =
      if (designParamList.length == 0) ""
      else "\ngeneric (" + designParamList.mkString("\n", ";\n", "\n").hindent(1) + ");"
    val portBlock = ports.emptyOr(v => s"""
         |port (
         |${ports.hindent}
         |);""".stripMargin)
    s"""entity ${entityName(design)} is$genericBlock$portBlock
       |end ${entityName(design)};""".stripMargin
  end csEntityDcl
  def archName(design: DFDesignBlock): String = s"${design.dclName}_arch"
  def csArchitectureDcl(design: DFDesignBlock): String =
    val designMembers = design.members(MemberView.Folded)
    // collecting all the vhdl named types that are used in conversion to/from bits
    val vhdlNamedConvDFTypes = design.members(MemberView.Flattened).view.flatMap {
      case alias: DFVal.Alias.AsIs =>
        val pf: PartialFunction[DFType, (DFVector | NamedDFType)] = {
          case dt: (DFVector | NamedDFType) => dt
        }
        (alias.dfType, alias.relValRef.get.dfType) match
          case (DFBits(_), fromDFType: (NamedDFType | ComposedDFType)) =>
            fromDFType.decompose(pf)
          case (toDFType: (NamedDFType | ComposedDFType), DFBits(_)) =>
            toDFType.decompose(pf)
          case _ => None
      case _ => None
    }.toSet
    // the vectors requiring conversion to/from bits
    val vectorsConvUsed = vhdlNamedConvDFTypes.collect { case dfType: DFVector =>
      printer.getVecDepthAndCellTypeName(dfType)._1
    }
    // In VHDL the vectors need to be named, and put in dependency order of other named types.
    // So first we prepare the vector type declarations in a mutable map and later we remove
    // entries that were already placed in the final type printing.
    val vectorTypeDcls =
      mutable.Map.from(printer.getLocalVectorTypes(design).view.map { (tpName, depth) =>
        val dclScope =
          if (vectorsConvUsed.contains(tpName)) DclScope.ArchBody else DclScope.TypeOnly
        tpName -> printer.csDFVectorDclsLocal(dclScope)(tpName, depth)
      })
    val globalNamedDFTypes = getSet.designDB.getGlobalNamedDFTypes
    // collect the local named types, including vectors
    val namedDFTypes = ListSet.from(getSet.designDB.designMemberTable(design).view.collect {
      case localVar @ DclVar()     => localVar.dfType
      case localConst @ DclConst() => localConst.dfType
    }.flatMap(_.decompose[DFVector | NamedDFType] {
      case dt: DFVector                                        => dt
      case dt: NamedDFType if !globalNamedDFTypes.contains(dt) => dt
    }))
    // declarations of the types and relevant functions
    val namedTypeConvFuncsDcl = namedDFTypes.view
      .flatMap {
        // vector types can have different dimensions, but we only need the declaration once
        case dfType: DFVector =>
          val tpName = printer.getVecDepthAndCellTypeName(dfType)._1
          vectorTypeDcls.get(tpName) match
            case Some(desc) =>
              vectorTypeDcls -= tpName
              Some(desc)
            case None => None
        case dfType: NamedDFType =>
          if (vhdlNamedConvDFTypes.contains(dfType))
            List(
              printer.csNamedDFTypeDcl(dfType, global = false),
              printer.csNamedDFTypeConvFuncsBody(dfType)
            )
          else Some(printer.csNamedDFTypeDcl(dfType, global = false))
      }
      .mkString("\n").emptyOr(x => s"$x\n")

    val dfValDcls =
      designMembers.view
        .flatMap {
          case p: DFVal.Dcl if p.isVar => Some(p)
          case DesignParam(_)          => None
          case c @ DclConst()          => Some(c)
          case _                       => None
        }
        .map(printer.csDFMember)
        .toList
        .emptyOr(_.mkString("\n"))
    val declarations =
      s"$namedTypeConvFuncsDcl$dfValDcls".emptyOr(v => s"\n${v.hindent}")
    val statements = csDFMembers(designMembers.filter {
      case _: DFVal.Dcl => false
      case DclConst()   => false
      case _            => true
    })
    s"""architecture ${archName(design)} of ${design.dclName} is$declarations
       |begin
       |${statements.hindent}
       |end ${archName(design)};""".stripMargin
  end csArchitectureDcl
  def csDFDesignBlockDcl(design: DFDesignBlock): String =
    s"""${csLibrary(design.inSimulation)}
       |
       |${csEntityDcl(design)}
       |
       |${csArchitectureDcl(design)}
       |""".stripMargin
  def csDFDesignBlockInst(design: DFDesignBlock): String =
    val body = csDFDesignLateBody(design)
    val designParamList = design.members(MemberView.Folded).collect { case param @ DesignParam(_) =>
      s"${param.getName} => ${param.relValRef.refCodeString}"
    }
    val designParamCS =
      if (designParamList.isEmpty) ""
      else " generic map (" + designParamList.mkString("\n", ",\n", "\n").hindent(1) + ")"
    val inst =
      s"${design.getName} : entity work.${entityName(design)}(${archName(design)})${designParamCS}"
    if (body.isEmpty) s"$inst;" else s"$inst port map (\n${body.hindent}\n);"
  def csDFDesignDefDcl(design: DFDesignBlock): String = printer.unsupported
  def csDFDesignDefInst(design: DFDesignBlock): String = printer.unsupported
  def csBlockBegin: String = ""
  def csBlockEnd: String = ""
  def csDFIfStatement(csCond: String): String = s"if $csCond then"
  def csDFElseStatement: String = "else"
  def csDFElseIfStatement(csCond: String): String = s"elsif $csCond then"
  def csDFIfEnd(lastCB: DFConditional.DFIfElseBlock): String = "end if;"
  def csIfBlockEmpty: String = "end if;"
  def csDFCaseBlockEmpty: String = ""
  def csDFCasePatternCatchAll: String = "others"
  def csDFCasePatternAlternativeData: String = " | "
  def csDFCasePatternStruct(pattern: Pattern.Struct): String = printer.unsupported
  def csDFCasePatternBind(pattern: Pattern.Bind): String = printer.unsupported
  def csDFCasePatternBindSI(pattern: Pattern.BindSI): String = printer.unsupported
  def csDFCaseKeyword: String = "when "
  def csDFCaseSeparator: String = " =>"
  def csDFCaseGuard(guardRef: DFConditional.Block.GuardRef): String = printer.unsupported
  def csDFMatchStatement(csSelector: String): String = s"case $csSelector is"
  def csDFMatchEnd: String = "end case;"
  def csProcessBlock(pb: ProcessBlock): String =
    val (statements, dcls) = pb
      .members(MemberView.Folded)
      .partition {
        case dcl: DFVal.Dcl                           => false
        case const: DFVal.Const if !const.isAnonymous => false
        case _                                        => true
      }
    val body = csDFMembers(statements)
    val dcl =
      if (dcls.isEmpty) ""
      else s"\n${csDFMembers(dcls).hindent}"
    val named = pb.meta.nameOpt.map(n => s"$n : ").getOrElse("")
    val senList = pb.sensitivity match
      case Sensitivity.All => " (all)"
      case Sensitivity.List(refs) =>
        if (refs.isEmpty) "" else s" ${refs.map(_.refCodeString).mkStringBrackets}"
    s"${named}process$senList$dcl\nbegin\n${body.hindent}\nend process;"
  end csProcessBlock
  def csDomainBlock(pb: DomainBlock): String = printer.unsupported
end VHDLOwnerPrinter
