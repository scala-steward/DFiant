package DFiant.compiler
package printing
import ir.*
import analysis.*
import DFiant.internals.*
import DFVal.*
import DFiant.compiler.ir.AlwaysBlock.Sensitivity
import DFiant.compiler.ir.DFConditional.DFCaseBlock.Pattern

trait AbstractOwnerPrinter extends AbstractPrinter:
  final def csDFOwnerBody(owner: DFOwner): String =
    csDFMembers(owner.members(MemberView.Folded))
  final def csDFMembers(members: List[DFMember]): String =
    members.view
      // selecting viewable members:
      .filter {
        // excluding binds
        case Bind(_) => false
        // an ident placeholder (can be anonymous)
        case Ident(_) => true
        // named members
        case m: DFMember.Named if !m.isAnonymous => true
        // including only nets that are not late connections
        case net: DFNet => !net.isLateConnection
        // including only conditional statements (no type) headers
        case ch: DFConditional.Header => ch.dfType == NoType
        // always blocks
        case ab: AlwaysBlock => true
        // the rest are not directly viewable
        case _ => false
      }
      .map(_.codeString)
      .filter(_.nonEmpty)
      .emptyOr(_.mkString("", s"${printer.csEndOfStatement}\n", printer.csEndOfStatement))
  final def csDFOwnerLateBody(owner: DFOwner): String =
    owner
      .members(MemberView.Folded)
      .view
      // selecting viewable members:
      .filter {
        // late construction nets
        case net: DFNet => net.isLateConnection
        // the rest are not directly viewable
        case _ => false
      }
      .map(_.codeString)
      .filter(_.nonEmpty)
      .mkString(s"${printer.csLateConnectionSep}\n")
  def csDFDesignBlockDcl(design: DFDesignBlock): String
  def csDFDesignBlockInst(design: DFDesignBlock): String
  def csDFIfStatement(csCond: String): String
  def csDFElseStatement: String
  def csDFElseIfStatement(csCond: String): String
  final def csDFIfElseStatement(ifBlock: DFConditional.DFIfElseBlock): String =
    ifBlock.prevBlockOrHeaderRef.get match
      case _: DFConditional.Header => csDFIfStatement(ifBlock.guardRef.refCodeString)
      case _ =>
        ifBlock.guardRef.get match
          case DFMember.Empty => csDFElseStatement
          case _              => csDFElseIfStatement(ifBlock.guardRef.refCodeString)
  def csDFIfEnd: String
  def csIfBlockEmpty: String
  def csDFCasePatternCatchAll: String
  def csDFCasePatternAlternativeToken: String
  def csDFCasePatternStruct(pattern: Pattern.Struct): String
  def csDFCasePatternBind(pattern: Pattern.Bind): String
  def csDFCasePatternBindSI(pattern: Pattern.BindSI): String
  def csDFCasePattern(pattern: Pattern): String = pattern match
    case Pattern.CatchAll         => csDFCasePatternCatchAll
    case Pattern.Singleton(token) => printer.csDFToken(token)
    case Pattern.Alternative(list) =>
      list.map(csDFCasePattern).mkString(csDFCasePatternAlternativeToken)
    case pattern: Pattern.Struct => csDFCasePatternStruct(pattern)
    case pattern: Pattern.Bind   => csDFCasePatternBind(pattern)
    case pattern: Pattern.BindSI => csDFCasePatternBindSI(pattern)
  def csDFCaseGuard(guardRef: DFConditional.Block.GuardRef): String
  def csDFCaseKeyword: String
  def csDFCaseSeparator: String
  final def csDFCaseStatement(caseBlock: DFConditional.DFCaseBlock): String =
    val csGuard =
      caseBlock.guardRef.get match
        case DFMember.Empty => ""
        case _              => csDFCaseGuard(caseBlock.guardRef)
    s"$csDFCaseKeyword ${csDFCasePattern(caseBlock.pattern)} $csGuard$csDFCaseSeparator"
  def csDFMatchStatement(csSelector: String): String
  def csDFMatchEnd: String
  final def csDFConditionalBlock(cb: DFConditional.Block): String =
    val body = csDFOwnerBody(cb)
    val statement = cb match
      case caseBlock: DFConditional.DFCaseBlock => csDFCaseStatement(caseBlock)
      case ifBlock: DFConditional.DFIfElseBlock => csDFIfElseStatement(ifBlock)
    val end =
      if (cb.isLastCB)
        cb match
          case caseBlock: DFConditional.DFCaseBlock => csDFMatchEnd
          case ifBlock: DFConditional.DFIfElseBlock => csDFIfEnd
      else ""
    val indentBody =
      if (body.contains("\n")) s"\n${body.indent}" else s" $body"
    if (body.isEmpty) cb match
      case caseBlock: DFConditional.DFCaseBlock => statement
      case ifBlock: DFConditional.DFIfElseBlock => s"$statement$csIfBlockEmpty"
    else s"$statement$indentBody${end.emptyOr(e => s"\n$e")}"
  end csDFConditionalBlock
  final def csDFConditional(ch: DFConditional.Header): String =
    val chain = getSet.designDB.conditionalChainTable(ch)
    val csChains = chain.map(ib => csDFConditionalBlock(ib)).mkString("\n")
    ch match
      case mh: DFConditional.DFMatchHeader =>
        val csSelector = mh.selectorRef.refCodeString.applyBrackets()
        s"$csSelector match\n${csChains.indent}"
      case ih: DFConditional.DFIfHeader => csChains
  def csAlwaysBlock(ab: AlwaysBlock): String
  def csDomainBlock(ab: DomainBlock): String
end AbstractOwnerPrinter

protected trait DFOwnerPrinter extends AbstractOwnerPrinter:
  def csDFDesignBlockDcl(design: DFDesignBlock): String =
    val localDcls = printer.csLocalTypeDcls(design)
    val body = csDFOwnerBody(design)
    val bodyWithDcls = if (localDcls.isEmpty) body else s"$localDcls\n\n$body"
    val dsnCls = design.domainType match
      case DomainType.DF    => "DFDesign"
      case _: DomainType.RT => "RTDesign"
      case _                => "EDDesign"
    val dcl = s"class ${design.dclName} extends $dsnCls"
    if (bodyWithDcls.isEmpty) dcl else s"$dcl:\n${bodyWithDcls.indent}\nend ${design.dclName}"
  end csDFDesignBlockDcl
  def csDFDesignBlockInst(design: DFDesignBlock): String =
    val body = csDFOwnerLateBody(design)
    val inst = s"val ${design.name} = new ${design.dclName}"
    if (body.isEmpty) inst else s"$inst:\n${body.indent}"
  def csDFIfStatement(csCond: String): String = s"if ($csCond)"
  def csDFElseStatement: String = "else"
  def csDFElseIfStatement(csCond: String): String = s"else if ($csCond)"
  def csDFIfEnd: String = ""
  def csIfBlockEmpty: String = " {}"
  def csDFCasePatternCatchAll: String = "_"
  def csDFCasePatternAlternativeToken: String = " | "
  def csDFCasePatternStruct(pattern: Pattern.Struct): String =
    pattern.name + pattern.fieldPatterns.map(csDFCasePattern).mkStringBrackets
  def csDFCasePatternBind(pattern: Pattern.Bind): String =
    val bindStr = pattern.pattern match
      case Pattern.CatchAll => ""
      case _                => s" @ ${csDFCasePattern(pattern.pattern)}"
    s"${pattern.ref.get.name}$bindStr"
  def csDFCasePatternBindSI(pattern: Pattern.BindSI): String =
    val csBinds = pattern.refs.view
      .map { r => r.get }
      .map(bindVal => s"$${${bindVal.name}: B[${bindVal.dfType.width}]}")
    val fullTerm = pattern.parts.coalesce(csBinds).mkString
    s"""${pattern.op}"$fullTerm""""
  def csDFCaseGuard(guardRef: DFConditional.Block.GuardRef): String =
    s"if ${guardRef.refCodeString} "
  def csDFCaseKeyword: String = "case"
  def csDFCaseSeparator: String = "=>"
  def csDFMatchEnd: String = ""
  def csDFMatchStatement(csSelector: String): String = s"$csSelector match"
  def csAlwaysBlock(ab: AlwaysBlock): String =
    val body = csDFOwnerBody(ab)
    val named = ab.meta.nameOpt.map(n => s"val $n = ").getOrElse("")
    val senList = ab.sensitivity match
      case Sensitivity.All        => ".all"
      case Sensitivity.List(refs) => refs.map(_.refCodeString).mkStringBrackets
    s"${named}always${senList} {\n${body.indent}\n}"
  def csDomainBlock(domain: DomainBlock): String =
    val body = csDFOwnerBody(domain)
    val named = domain.meta.nameOpt.map(n => s"val $n = ").getOrElse("")
    val domainStr = domain.domainType match
      case df: DomainType.DF => "DFDomain"
      case rt: DomainType.RT => "RTDomain()"
      case ed: DomainType.ED => "EDDomain"
    s"${named}new $domainStr:\n${body.indent}"

end DFOwnerPrinter
