package DFiant.core
import DFiant.compiler.ir
import DFiant.internals.*
import scala.quoted.*
import collection.immutable.ListMap
import ir.DFVal.Func.Op as FuncOp
import scala.annotation.unchecked.uncheckedVariance

type FieldsOrTuple = DFStruct.Fields | NonEmptyTuple
type DFStruct[+F <: FieldsOrTuple] =
  DFType[ir.DFStruct, Args1[F @uncheckedVariance]]
object DFStruct:
  abstract class Fields extends Product with Serializable
  private[core] def apply[F <: FieldsOrTuple](
      name: String,
      fieldMap: ListMap[String, DFTypeAny]
  ): DFStruct[F] =
    ir.DFStruct(name, fieldMap.map((n, t) => (n, t.asIRForced))).asFE[DFStruct[F]]
  private[core] def apply[F <: FieldsOrTuple](
      name: String,
      fieldNames: List[String],
      fieldTypes: List[DFTypeAny]
  ): DFStruct[F] =
    apply[F](name, ListMap(fieldNames.lazyZip(fieldTypes).toSeq*))
  private[core] def apply(product: FieldsOrTuple): DFStruct[FieldsOrTuple] =
    unapply(
      product
    ).get
  private[core] def unapply(
      product: FieldsOrTuple
  ): Option[DFStruct[FieldsOrTuple]] =
    val fieldTypes = product.productIterator.map {
      case dfVal: DFValAny =>
        dfVal.dfType
      case _ => return None
    }.toList
    val fieldNames = product.productElementNames.toList
    Some(DFStruct(product.productPrefix, fieldNames, fieldTypes))

  inline given apply[F <: FieldsOrTuple]: DFStruct[F] = ${ dfTypeMacro[F] }
  def dfTypeMacro[F <: FieldsOrTuple](using
      Quotes,
      Type[F]
  ): Expr[DFStruct[F]] =
    import quotes.reflect.*
    val fTpe = TypeRepr.of[F]
    val (structName, fields) = fTpe.asTypeOf[Any] match
      case '[NonEmptyTuple] =>
        (
          "",
          fTpe.getTupleArgs.zipWithIndex.map((t, i) => (s"_${i + 1}", t.asTypeOf[Any]))
        )
      case _ =>
        val clsSym = fTpe.classSymbol.get
        (
          clsSym.name.toString,
          clsSym.caseFields.view
            .map(m => (m.name.toString, fTpe.memberType(m).asTypeOf[Any]))
        )
    val fieldErrors = fields.filter {
      case (_, '[DFValOf[t]]) => false
      case _                  => true
    }.toList
    if (fieldErrors.isEmpty)
      val fieldNames: List[Expr[String]] = fields.map((n, _) => Expr(n)).toList
      val fieldTypes: List[Expr[DFTypeAny]] = fields.collect { case (_, '[DFValOf[t]]) =>
        '{ compiletime.summonInline[t] }
      }.toList
      val fieldNamesExpr = Varargs(fieldNames)
      val fieldTypesExpr = Varargs(fieldTypes)
      val nameExpr = Expr(structName)
      '{
        DFStruct
          .apply[F]($nameExpr, List($fieldNamesExpr*), List($fieldTypesExpr*))
      }
    else
      val fieldTypesStr = fieldErrors
        .map { case (n, '[t]) =>
          s"${n}: ${TypeRepr.of[t].showType}"
        }
        .mkString("\n")
      val intro = if (structName.isEmpty) "tuple" else s"struct `$structName`"
      val msg =
        s"""The $intro has invalid dataflow value field types. 
           |A valid field type is in the form of [DFType] <> VAL.
           |The following fields do not match this pattern:
           |$fieldTypesStr""".stripMargin
      '{ compiletime.error(${ Expr(msg) }) }
    end if
  end dfTypeMacro

  type Token[+F <: FieldsOrTuple] = DFToken[DFStruct[F]]
  object Token:
    def apply[F <: FieldsOrTuple](dfType: DFStruct[F], value: F): Token[F] =
      val data = value.productIterator.map { case dfVal: DFVal[_, _] =>
        dfVal.asIRForced match
          case ir.DFVal.Const(token, _, _, _) => token.data
          case v =>
            throw new IllegalArgumentException(
              s"Tokens must only be constant but found the value: ${v}"
            )
      }.toList
//      println(dfType.asIRForced.fieldMap.values.toList)
//      println(data)
      ir.DFToken.forced(dfType.asIRForced, data).asTokenOf[DFStruct[F]]
    object TC:
      import DFToken.TC
      given DFStructTokenFromCC[
          F <: FieldsOrTuple
      ]: TC[DFStruct[F], F] with
        def conv(dfType: DFStruct[F], value: F): Out = Token(dfType, value)

    object Compare:
      import DFToken.Compare
      given DFTupleTokenFromTuple[
          F <: FieldsOrTuple,
          Op <: FuncOp,
          C <: Boolean
      ]: Compare[DFStruct[F], F, Op, C] with
        def conv(dfType: DFStruct[F], value: F): Out = Token(dfType, value)
    end Compare
  end Token

  object Val:
    private[core] def unapply(
        fields: FieldsOrTuple
    )(using DFC): Option[DFValOf[DFStruct[FieldsOrTuple]]] =
      fields match
        case DFStruct(dfType) =>
          val dfVals = fields.productIterator.map { case dfVal: DFValAny =>
            dfVal
          }.toList
          Some(DFVal.Func(dfType, FuncOp.++, dfVals)(using dfc.anonymize))
        case _ => None
    object TC:
      import DFVal.TC
      given DFStructValFromCC[
          F <: FieldsOrTuple
      ](using DFC): TC[DFStruct[F], F] with
        def conv(dfType: DFStruct[F], value: F): Out =
          val dfVals = value.productIterator.map { case dfVal: DFVal[_, _] =>
            dfVal
          }.toList
          DFVal.Func(dfType, FuncOp.++, dfVals)(using dfc.anonymize)
    object Compare:
      import DFVal.Compare
      given DFStructArg[
          F <: FieldsOrTuple,
          Op <: FuncOp,
          C <: Boolean
      ](using DFC): Compare[DFStruct[F], F, Op, C] with
        def conv(dfType: DFStruct[F], value: F): Out =
          val dfVals = value.productIterator.map { case dfVal: DFVal[_, _] =>
            dfVal
          }.toList
          DFVal.Func(dfType, FuncOp.++, dfVals)(using dfc.anonymize)
    end Compare
  end Val
end DFStruct
