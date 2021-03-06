package DFiant

import DFiant.EdgeDetect.Edge
import compiler.csprinter.CSPrinter
abstract class DFInlineComponent[Type <: DFAny.Type](dfType : Type)(
  implicit ctx : ContextOf[DFInlineComponent[_]]
) extends DFDesign with DFAny.Of[Type] {
  val rep : DFInlineComponent.Rep
  override private[DFiant] lazy val inlinedRep : Option[DFInlineComponent.Rep] = Some(rep)
  final val outPort = DFAny.Port.Out(dfType)
  final val member : DFAny.Member = outPort
}
object DFInlineComponent {
  trait Rep extends Product with Serializable {
    protected[DFiant] def =~(that : DFInlineComponent.Rep)(implicit getSet : MemberGetSet) : Boolean
    def inlineCodeString(implicit printer: CSPrinter, owner: DFOwner) : String
  }
  type Ref = DFMember.Ref.Of[Ref.Type, DFAny.Member]
  object Ref {
    trait Type extends DFMember.Ref.Type
    implicit val ev : Type = new Type {}
  }

  object Block {
    def unapply(arg : DFDesign.Block) : Option[Rep] = arg match {
      case DFDesign.Block.Internal(_,_,_,someRep) => someRep
      case _ => None
    }
  }
}

final case class EdgeDetect(bit : DFBit, edge : EdgeDetect.Edge)(
  implicit ctx : ContextOf[EdgeDetect]
) extends DFInlineComponent[DFBool.Type](DFBool.Type(logical = true)) {
  lazy val rep : DFInlineComponent.Rep = EdgeDetect.Rep(bit, edge)
  private val boolIn = DFBit <> IN
  edge match {
    case Edge.Falling => outPort <> (!boolIn && boolIn.prev)
    case Edge.Rising => outPort <> (boolIn && !boolIn.prev)
  }
  atOwnerDo {
    boolIn.connect(bit)
  }
}
object EdgeDetect {
  sealed trait Edge extends Product with Serializable
  object Edge {
    case object Rising extends Edge
    case object Falling extends Edge
  }
  final case class Rep(bitRef : DFInlineComponent.Ref, edge : EdgeDetect.Edge) extends DFInlineComponent.Rep {
    protected[DFiant] def =~(that : DFInlineComponent.Rep)(implicit getSet : MemberGetSet) : Boolean = that match {
      case Rep(bitRef, edge) => this.bitRef =~ bitRef && this.edge == edge
      case _ => false
    }
    def inlineCodeString(implicit printer: CSPrinter, owner: DFOwner) : String = edge match {
      case Edge.Rising => s"${bitRef.refCodeString}.rising()"
      case Edge.Falling => s"${bitRef.refCodeString}.falling()"
    }
  }
  object Rep {
    def apply(bit : DFAny.Member, edge : Edge)(implicit ctx : DFNet.Context) : Rep = new Rep(bit, edge)
    object Unref {
      def unapply(arg : Rep)(implicit getSet: MemberGetSet) : Option[(DFAny.Member, Edge)] = arg match {
        case Rep(bitRef, edge) => Some((bitRef.get, edge))
        case _ => None
      }
    }
  }
}