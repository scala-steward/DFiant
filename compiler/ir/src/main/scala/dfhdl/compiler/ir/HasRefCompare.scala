package dfhdl.compiler.ir
import scala.collection.immutable.ListMap

trait HasRefCompare[T <: HasRefCompare[T]]:
  private var cachedCompare: Option[(T, Boolean)] = None
  final def =~(that: T)(using MemberGetSet): Boolean =
    cachedCompare match
      case Some(prevCompare, result) if prevCompare eq that => result
      case _                                                =>
        val res = this `prot_=~` that
        cachedCompare = Some(that, res)
        res
  protected def `prot_=~`(that: T)(using MemberGetSet): Boolean
  lazy val getRefs: List[DFRef.TwoWayAny]
  def copyWithNewRefs(using RefGen): this.type

object HasRefCompare:
  extension [T <: HasRefCompare[T]](list: List[T])
    def =~(that: List[T])(using MemberGetSet): Boolean =
      list.length == that.length && list.lazyZip(that).forall(_ =~ _)
  extension [T <: HasRefCompare[T]](list: ListMap[String, T])
    def =~(that: ListMap[String, T])(using MemberGetSet): Boolean =
      list.size == that.size && list.lazyZip(that).forall {
        case ((k1, v1), (k2, v2)) => k1 == k2 && v1 =~ v2
      }
