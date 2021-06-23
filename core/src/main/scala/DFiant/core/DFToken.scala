package DFiant.core
import DFiant.compiler.printing.Printer
import DFiant.compiler.ir
import DFiant.internals.*

opaque type DFToken = ir.DFType.Token
object DFToken:
  extension (of: DFToken)
    def asIR: ir.DFType.Token = of
    def codeString(using printer: Printer): String = printer.csDFToken(asIR)
  opaque type Of[+T <: DFType, D] <: DFToken = DFToken
  object Of:
    extension [T <: DFType, D](token: Of[T, D])
      def data: D = token.asIR.data.asInstanceOf[D]
      def dfType: T = token.asIR.dfType.asInstanceOf[T]
      def width(using w: Width[T]): Inlined.Int[w.Out] =
        Inlined.Int.forced[w.Out](token.asIR.width)
  trait TC[V, -T <: DFType]:
    type Out <: DFToken
    def apply(value: V, dfType: T): Out
  object TC:
    given DFBoolTokenFromBoolean[V <: Boolean]: TC[V, DFBoolOrBit] with
      type Out = DFBoolOrBit.Token
      def apply(value: V, dfType: DFBoolOrBit): Out = ???
  end TC

//final case class DFToken[+T <: DFType](dfType: T)(val data: dfType.TokenData)
//    extends NCCode:
//  def ==[R <: DFType](rhs: DFToken[R]): DFBool.Token =
//    dfType.tokenEquals(this, rhs)
//  protected[DFiant] lazy val (valueBits: BitVector, bubbleBits: BitVector) =
//    dfType.tokenDataToBits(data)
//  def codeString(using Printer): String = dfType.tokenCodeString(data)
//
//object DFToken:
//  extension [T <: DFType](token: DFToken[T])(using w: Width[T])
//    def width: Inlined.Int[w.Out] =
//      Inlined.Int.forced[w.Out](token.dfType.width)
//    def bits: DFToken[DFBits[w.Out]] =
//      DFBits.Token(width)(token.valueBits, token.bubbleBits)
//
//  extension [W <: Int](token: DFBits.Token[W])
//    def as[T <: DFType](dfType: T)(using w: Width[T]): DFToken[T] =
//      DFToken(dfType)(dfType.tokenBitsToData(token.valueBits, token.bubbleBits))
