package dfhdl.core
import dfhdl.compiler.ir
import ir.DFVal.Func.Op as FuncOp
import DFVal.TC
import DFVal.Compare
final class BoolSelWrapper[SP, OT, OF](
    val sel: DFValTP[DFBoolOrBit, SP],
    val onTrue: OT,
    val onFalse: OF
):
  transparent inline def ==[RT <: DFTypeAny, RM <: ModifierAny](
      inline that: DFVal[RT, RM]
  )(using DFC): DFValTP[DFBool, Any] =
    DFVal.Ops.compare[FuncOp.===.type, this.type, DFVal[RT, RM]](this, that)

  transparent inline def !=[RT <: DFTypeAny, RM <: ModifierAny](
      inline that: DFVal[RT, RM]
  )(using DFC): DFValTP[DFBool, Any] =
    DFVal.Ops.compare[FuncOp.=!=.type, this.type, DFVal[RT, RM]](this, that)

  private var memoizedSelection: Option[DFValAny] = None
  protected def setMemoizedSelection[T <: DFTypeAny](
      dfType: T,
      onTrue: DFValOf[T],
      onFalse: DFValOf[T]
  )(using DFC): Unit =
    if (memoizedSelection.isEmpty)
      memoizedSelection = Some(
        DFVal.Func(dfType, FuncOp.sel, List(sel, onTrue, onFalse))
      )
  protected def getMemoizedSelection: DFValAny = memoizedSelection.get
end BoolSelWrapper

object BoolSelWrapper:
  given [T <: DFTypeAny, SP, OT, OTP, OF, OFP](using
      tcOT: TC[T, OT] { type OutP = OTP },
      tcOF: TC[T, OF] { type OutP = OFP }
  ): TC[T, BoolSelWrapper[SP, OT, OF]] with
    type OutP = SP | OTP | OFP
    def conv(dfType: T, value: BoolSelWrapper[SP, OT, OF])(using DFC): Out =
      value.setMemoizedSelection(dfType, tcOT(dfType, value.onTrue), tcOF(dfType, value.onFalse))
      value.getMemoizedSelection.asValTP[T, OutP]

  given [
      T <: DFTypeAny,
      SP,
      OT,
      OTP,
      OF,
      OFP,
      Op <: FuncOp,
      C <: Boolean,
      R <: BoolSelWrapper[SP, OT, OF]
  ](
      using
      tcOT: Compare[T, OT, Op, C] { type OutP = OTP },
      tcOF: Compare[T, OF, Op, C] { type OutP = OFP }
  ): Compare[T, R, Op, C] with
    type OutP = SP | OTP | OFP
    def conv(dfType: T, value: R)(using DFC): Out =
      value.setMemoizedSelection(
        dfType,
        tcOT.conv(dfType, value.onTrue),
        tcOF.conv(dfType, value.onFalse)
      )
      value.getMemoizedSelection.asValTP[T, OutP]
  end given

end BoolSelWrapper
