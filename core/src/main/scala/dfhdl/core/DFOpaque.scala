package dfhdl.core
import dfhdl.compiler.ir
import dfhdl.compiler.printing.{DefaultPrinter, Printer}
import dfhdl.internals.*
import scala.quoted.*

import scala.annotation.unchecked.uncheckedVariance

type DFOpaque[+T <: DFOpaque.Abstract] =
  DFType[ir.DFOpaque, Args1[T @uncheckedVariance]]
object DFOpaque:
  protected[core] sealed trait Abstract extends HasTypeName, ir.DFOpaque.CustomId:
    type ActualType <: DFTypeAny
    protected[core] val actualType: ActualType

  abstract class Frontend[T <: DFTypeAny](final protected[core] val actualType: T) extends Abstract:
    type ActualType = T

  given [T <: Abstract](using ce: ClassEv[T]): DFOpaque[T] = DFOpaque(ce.value)

  def apply[T <: Abstract](
      t: T
  ): DFOpaque[T] =
    ir.DFOpaque(t.typeName, t, t.actualType.asIR).asFE[DFOpaque[T]]
  extension [T <: DFTypeAny, TFE <: Frontend[T]](dfType: DFOpaque[TFE])
    def actualType: T = dfType.asIR.actualType.asFE[T]

  type Token[T <: Abstract] = DFToken[DFOpaque[T]]
  object Token:
    def apply[T <: DFTypeAny, TFE <: Frontend[T]](
        tfe: TFE,
        token: T <> TOKEN
    ): Token[TFE] =
      ir.DFToken(DFOpaque(tfe).asIR)(token.asIR.data).asTokenOf[DFOpaque[TFE]]
    def forced[TFE <: Abstract](
        tfe: TFE,
        token: DFTokenAny
    ): Token[TFE] =
      ir.DFToken(DFOpaque(tfe).asIR)(token.asIR.data).asTokenOf[DFOpaque[TFE]]

    object Ops:
      extension [T <: DFTypeAny, TFE <: Frontend[T]](
          lhs: DFOpaque[TFE] <> TOKEN
      )
        def actual: T <> TOKEN =
          lhs.asIR.data.asInstanceOf[ir.DFTokenAny].asTokenOf[T]
  end Token

  object Val:
    object Ops:
      extension [L](inline lhs: L)
        transparent inline def as[Comp <: AnyRef](tfeComp: Comp): Any = ${ asMacro[L, Comp]('lhs) }
      extension [T <: DFTypeAny](lhs: Vector[DFValOf[T]])
        transparent inline def as[Comp <: AnyRef, D <: Int](
            tfeComp: Comp
        )(using
            cc: CaseClass[Comp, Abstract]
        ): DFValOf[DFOpaque[cc.CC]] = // Frontend[DFVector[T, Tuple1[_ <: Int]]]
          ???
      private def asMacro[L, Comp <: AnyRef](
          lhs: Expr[L]
      )(using Quotes, Type[L], Type[Comp]): Expr[Any] =
        import quotes.reflect.*
        val tfeTpe = TypeRepr.of[Comp].getCompanionClassTpe
        tfeTpe.baseType(TypeRepr.of[Frontend[_ <: DFTypeAny]].typeSymbol) match
          case AppliedType(_, tTpe :: _) =>
            val tType = tTpe.asTypeOf[DFTypeAny]
            val tfeType = tfeTpe.asTypeOf[Abstract]
            val tfe = '{
              compiletime
                .summonInline[ClassEv[tfeType.Underlying]]
                .value
            }
            val lhsTerm = lhs.asTerm.exactTerm
            val lhsTpe = lhsTerm.tpe
            val lhsExpr = lhsTerm.asExpr
            val lhsType = lhsTpe.asTypeOf[Any]
            val tExpr = '{ $tfe.actualType.asInstanceOf[tType.Underlying] }
            def hasDFVal(tpe: TypeRepr): Boolean =
              tpe.asTypeOf[Any] match
                case '[DFValAny] => true
                case '[ValueOf[t]] =>
                  hasDFVal(TypeRepr.of[t])
                case '[NonEmptyTuple] =>
                  tpe.getTupleArgs.exists(hasDFVal)
                case _ => false
            if (hasDFVal(lhsTpe))
              '{
                val tc = compiletime.summonInline[DFVal.TC[tType.Underlying, lhsType.Underlying]]
                trydf {
                  DFVal.Alias.AsIs(
                    DFOpaque[tfeType.Underlying]($tfe),
                    tc($tExpr, $lhsExpr),
                    Token.forced[tfeType.Underlying]($tfe, _)
                  )(using compiletime.summonInline[DFC])
                }(using compiletime.summonInline[DFC])
              }
            else
              '{
                val tc =
                  compiletime.summonInline[DFToken.TC[tType.Underlying, lhsType.Underlying]]
                Token.forced[tfeType.Underlying]($tfe, tc($tExpr, $lhsExpr))
              }
          case _ =>
            report.errorAndAbort("Not a valid opaque type companion.")
        end match
      end asMacro

      extension [T <: DFTypeAny, TFE <: Frontend[T], A, C, I](
          lhs: DFVal[DFOpaque[TFE], Modifier[A, C, I]]
      )
        def actual(using DFC): DFVal[T, Modifier[A, Any, Any]] = trydf {
          import Token.Ops.{actual => actualToken}
          DFVal.Alias.AsIs(lhs.dfType.actualType, lhs, _.actualToken)
        }
    end Ops
  end Val

end DFOpaque
