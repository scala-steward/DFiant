package dfhdl.compiler
package analysis
import dfhdl.internals.*
import ir.*
import DFConditional.DFCaseBlock.Pattern
import DFVal.Modifier

import scala.annotation.tailrec
import scala.reflect.classTag

object Ident:
  def unapply(alias: ir.DFVal.Alias.AsIs)(using
      MemberGetSet
  ): Option[ir.DFVal] =
    val relVal = alias.relValRef.get
    if (alias.dfType == relVal.dfType) Some(relVal)
    else None

object Bind:
  def unapply(alias: ir.DFVal.Alias)(using
      MemberGetSet
  ): Option[ir.DFVal] =
    if (alias.getTagOf[Pattern.Bind.Tag.type].isDefined)
      Some(alias.relValRef.get)
    else None

object DclVar:
  def unapply(dcl: DFVal.Dcl)(using
      MemberGetSet
  ): Boolean = dcl.modifier match
    case Modifier.VAR => true
    case _            => false

object DclIn:
  def unapply(dcl: DFVal.Dcl)(using
      MemberGetSet
  ): Boolean = dcl.modifier match
    case Modifier.IN => true
    case _           => false

object DclOut:
  def unapply(dcl: DFVal.Dcl)(using
      MemberGetSet
  ): Boolean = dcl.modifier match
    case Modifier.OUT => true
    case _            => false

object RegDomain:
  def unapply(dfVal: DFVal)(using MemberGetSet): Option[RTDomainCfg] =
    dfVal match
      case reg: DFVal.Alias.History =>
        reg.op match
          case DFVal.Alias.History.Op.Reg(domainCfg) => Some(domainCfg)
          case _                                     => None
      case reg: DFVal.Dcl =>
        reg.modifier match
          case Modifier.REG(domainCfg) => Some(domainCfg)
          case _                       => None
      case _ => None

extension (dcl: DFVal.Dcl)
  def externalInit: Option[List[DFTokenAny]] = dcl.getTagOf[ExternalInit].map(_.tokenSeq)

private val netClassTag = classTag[DFNet]
private val aliasPartClassTag = classTag[DFVal.Alias.Partial]

extension (dfVal: DFVal)
  def originRefs(using MemberGetSet): Set[DFRefAny] =
    getSet.designDB.memberTable(dfVal).collect { case r: DFRef.TwoWayAny => r.originRef }
  def getPartialAliases(using MemberGetSet): Set[DFVal.Alias.Partial] =
    dfVal.originRefs.flatMap {
      case r if r.refType equals aliasPartClassTag =>
        r.get match
          case alias: DFVal.Alias.Partial => Some(alias)
          case _                          => None
      case _ => None
    }
  def hasPrevAlias(using MemberGetSet): Boolean =
    val refs = dfVal.originRefs
    refs.exists { r =>
      r.get match
        case history: DFVal.Alias.History if (history.op == DFVal.Alias.History.Op.Prev) =>
          true
        case alias: DFVal.Alias =>
          alias.hasPrevAlias
        case _ => false
    }
  end hasPrevAlias
  def getConnectionTo(using MemberGetSet): Option[DFNet] =
    dfVal.dealias.flatMap(getSet.designDB.connectionTable.get)
  def getConnectionsFrom(using MemberGetSet): List[DFNet] =
    getSet.designDB.connectionTableInverted.getOrElse(dfVal, Nil)
  def getAssignmentsTo(using MemberGetSet): Set[DFVal] =
    getSet.designDB.assignmentsTable.getOrElse(dfVal, Set())
  def getAssignmentsFrom(using MemberGetSet): Set[DFVal] =
    getSet.designDB.assignmentsTableInverted.getOrElse(dfVal, Set())
  def getReadDeps(using MemberGetSet): Set[DFNet | DFVal] =
    val refs = dfVal.originRefs
    refs.flatMap { r =>
      r.get match
        case net: DFNet =>
          net match
            // ignoring
            case DFNet.Connection(toVal: DFVal, _, _) if toVal == dfVal => None
            case DFNet.Assignment(toVal, _) if toVal == dfVal           => None
            case _                                                      => Some(net)
        case alias: DFVal.Alias.Partial => alias.getReadDeps
        case dfVal: DFVal               => Some(dfVal)
        case _                          => None
    }
  end getReadDeps

  private def partName(member: DFVal) = s"${member.name}_part"
  @tailrec private def suggestName(
      member: DFVal,
      prevMember: Option[DFVal] = None
  )(using MemberGetSet): Option[String] =
    val refs = member.originRefs

    val refOwner: Option[DFMember] = refs
      // search consuming references first
      .collectFirst { case r if !(r.refType equals aliasPartClassTag) => r.get }
      // search aliasing references, as long as we don't go back to previous member
      // (aliasing can be used for both producing and consuming)
      .orElse {
        refs.collectFirst {
          case r if prevMember.isEmpty || prevMember.get != r.get => r.get
        }
      }
    refOwner match
      // name from assignment destination
      case Some(DFNet.Assignment(toVal, _)) => Some(partName(toVal))
      // name from connection destination
      case Some(DFNet.Connection(toVal: DFVal, _, _)) => Some(partName(toVal))
      // name from a named value which was referenced by an alias
      case Some(value: DFVal) if !value.isAnonymous => Some(partName(value))
      // found an (anonymous) value -> checking suggestion for it
      case Some(value: DFVal) => suggestName(value, Some(value))
      // no named source found
      case _ => None
  end suggestName
  def suggestName(using MemberGetSet): Option[String] = suggestName(dfVal)
end extension