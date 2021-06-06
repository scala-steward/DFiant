package DFiant
package core
import compiler.printing.*
import internals.*
import scala.annotation.targetName
import compiletime.*
import scala.quoted.*
import collection.mutable
import collection.immutable.ListMap

sealed trait DFType extends NCCode: //, Product, Serializable
  protected val width: Int

object DFType:
  private def apply(t: Any): DFType =
    t match
      case dfType: DFType       => dfType
      case tuple: NonEmptyTuple => DFTuple(tuple)
      case fields: DFFields     => DFStruct(fields)
      //TODO: need to add proper upper-bound if fixed in Scalac
      //see: https://contributors.scala-lang.org/t/missing-dedicated-class-for-enum-companions
      case enumCompanion: AnyRef => DFEnum(enumCompanion)

  sealed trait DFMatchable extends DFType
  sealed trait DFFlattenable extends DFType
  def tcMacro[T <: AnyRef](using Quotes, Type[T]): Expr[TC[T]] =
    import quotes.reflect.*
    val tTpe = TypeRepr.of[T]
    val dfTypeTpe = TypeRepr.of[DFType]
    val nonEmptyTupleTpe = TypeRepr.of[NonEmptyTuple]
    val fieldTpe = TypeRepr.of[DFField[_]]
    val fieldsTpe = TypeRepr.of[DFFields]
    val encodingSym = TypeRepr.of[DFEncoding].typeSymbol
    def checkSupported(tTpe: TypeRepr): Unit = {
      // println((tTpe.show, expr.show))
      tTpe match
        case t if t <:< dfTypeTpe =>
        case applied: AppliedType if applied <:< nonEmptyTupleTpe =>
          applied.args.foreach(checkSupported)
        case t if t <:< fieldsTpe =>
        case DFEnum(_)            =>
        case t =>
          report.error(s"Unsupported dataflow type can be found for: ${t.show}")
    }

    checkSupported(tTpe)
    tTpe match
      case t if t <:< nonEmptyTupleTpe =>
        '{
          new TC[T]:
            type Type = DFTuple[T]
            def apply(t: T): Type = DFTuple[T](t)
        }
      case DFEnum(entries) =>
        val clsType = entries.head.asType
        clsType match
          case '[e] =>
            '{
              new TC[T]:
                type Type = DFEnum[T, e]
                def apply(t: T): Type = DFEnum[T, e](t)
            }

  trait TC[T]:
    type Type <: DFType
    def apply(t: T): Type

  transparent inline given ofDFType[T <: DFType]: TC[T] =
    new TC[T]:
      type Type = T
      def apply(t: T): Type = t
  transparent inline given ofDFFields[T <: DFFields]: TC[T] =
    new TC[T]:
      type Type = DFStruct[T]
      def apply(t: T): Type = DFStruct[T](t)
  transparent inline given ofAnyRef[T <: AnyRef]: TC[T] = ${ tcMacro[T] }

  type Supported = AnyRef //DFType | NonEmptyTuple
  object Ops:
    extension [T <: Supported](t: T)(using tc: TC[T])
      def dfType: tc.Type = tc(t)
      def width(using w: Width[T]): Inlined.Int[w.Out] =
        Inlined.Int.forced[w.Out](dfType.width)
      def codeString(using Printer): String = dfType.codeString
      // transparent inline def X(inline cellDim: Int*): DFType =
      //   x(dfType, cellDim: _*)
      inline def X(
          inline cellDim: Int
      ): DFVector[tc.Type, Tuple1[cellDim.type]] =
        DFVector(dfType, Tuple1(cellDim))
      inline def X(
          inline cellDim0: Int,
          inline cellDim1: Int
      ): DFVector[tc.Type, Tuple2[cellDim0.type, cellDim1.type]] =
        DFVector(dfType, Tuple2(cellDim0, cellDim1))
      inline def X(
          inline cellDim0: Int,
          inline cellDim1: Int,
          inline cellDim2: Int
      ): DFVector[tc.Type, Tuple3[
        cellDim0.type,
        cellDim1.type,
        cellDim2.type
      ]] =
        DFVector(dfType, Tuple3(cellDim0, cellDim1, cellDim2))
      transparent inline def opaque(using MetaContext): DFOpaque[_] =
        DFOpaque(dfType)
      def <>(dir: Int): Unit = {}

  // transparent inline def x[T <: DFType](
  //     cellType: T,
  //     inline cellDim: Int*
  // ): DFType =
  //   ${ xMacro('cellType, 'cellDim) }
  // def xMacro[T <: DFType](cellType: Expr[T], cellDim: Expr[Seq[Int]])(using
  //     Quotes,
  //     Type[T]
  // ): Expr[DFType] =
  //   import quotes.reflect.*
  //   val (tpe, tpl) = cellDim match
  //     case Varargs(argExprs) =>
  //       argExprs match
  //         case arg :: Nil =>
  //           println(arg)
  //           val tp = ConstantType(IntConstant(5)).asType
  //           (
  //             TypeRepr.of[Tuple1[5]].asType.asInstanceOf[Type[Int]],
  //             '{ Tuple1($arg) }
  //           )
  //   '{ new DFVector[T, tpe.Underlying]($cellType, $tpl) }

  trait Width[T <: Supported]:
    type Out <: Int
  transparent inline given [T <: Supported]: Width[T] = ${ getWidthMacro[T] }
  extension (using quotes: Quotes)(dfTpe: quotes.reflect.TypeRepr)
    def calcWidth: Option[Int] =
      import quotes.reflect.*
      dfTpe match
        case t if dfTpe <:< TypeRepr.of[DFBoolOrBit] => Some(1)
        case applied: AppliedType if applied <:< TypeRepr.of[DFBits[_]] =>
          applied.args.head match
            case ConstantType(IntConstant(value)) => Some(value)
            case _                                => None
        case applied: AppliedType if applied <:< TypeRepr.of[DFOpaque[_]] =>
          applied.args.head.calcWidth
        case applied: AppliedType if applied <:< TypeRepr.of[DFVector[_, _]] =>
          val cellWidth = applied.args.head.calcWidth
          val cellDims = applied.args.last.asInstanceOf[AppliedType].args
          val widths = cellWidth :: cellDims.map {
            case ConstantType(IntConstant(value)) => Some(value)
            case _                                => None
          }
          if (widths.forall(_.nonEmpty)) Some(widths.flatten.reduce(_ * _))
          else None
        case applied: AppliedType if applied <:< TypeRepr.of[NonEmptyTuple] =>
          val widths = applied.args.map(a => a.calcWidth)
          if (widths.forall(_.nonEmpty)) Some(widths.flatten.sum)
          else None
        case applied: AppliedType if applied <:< TypeRepr.of[DFTuple[_]] =>
          applied.args.head.calcWidth
        case fieldsTpe if fieldsTpe <:< TypeRepr.of[DFFields] =>
          val fieldTpe = TypeRepr.of[DFField[_]]
          val clsSym = fieldsTpe.classSymbol.get
          val widths =
            clsSym.memberFields.view
              .map(fieldsTpe.memberType)
              .collect {
                case applied: AppliedType if applied <:< fieldTpe =>
                  applied.args.head.calcWidth
              }
          if (widths.forall(_.nonEmpty)) Some(widths.flatten.sum)
          else None
        case DFEnum(entries) =>
          val entryCount = entries.length
          entries.head match
            case DFEncoding.StartAt(startTpe) =>
              startTpe match
                case ConstantType(IntConstant(value)) =>
                  Some((entryCount - 1 + value).bitsWidth(false))
                case _ => None
            case t if t <:< TypeRepr.of[DFEncoding.OneHot] =>
              Some(entryCount)
            case t if t <:< TypeRepr.of[DFEncoding.Grey] =>
              Some((entryCount - 1).bitsWidth(false))
            case DFEncoding.Manual(widthTpe) =>
              widthTpe match
                case ConstantType(IntConstant(value)) =>
                  Some(value)
                case _ => None
  def getWidthMacro[T <: Supported](using Quotes, Type[T]): Expr[Width[T]] =
    import quotes.reflect.*
    val tTpe = TypeRepr.of[T]
    val widthTpe: Type[Int] =
      tTpe.calcWidth
        .map(w => ConstantType(IntConstant(w)))
        .getOrElse(TypeRepr.of[Int])
        .asType
        .asInstanceOf[Type[Int]]
    '{
      new Width[T] {
        type Out = widthTpe.Underlying
      }
    }

  /////////////////////////////////////////////////////////////////////////////
  // DFBool or DFBit
  /////////////////////////////////////////////////////////////////////////////
  sealed trait DFBoolOrBit extends DFMatchable:
    final protected[DFType] val width = 1

  case object DFBool extends DFBoolOrBit:
    def codeString(using Printer): String = "DFBool"
  case object DFBit extends DFBoolOrBit:
    def codeString(using Printer): String = "DFBit"
  /////////////////////////////////////////////////////////////////////////////

  /////////////////////////////////////////////////////////////////////////////
  // DFBits
  /////////////////////////////////////////////////////////////////////////////
  final case class DFBits[W <: Int] private (
      protected[DFType] val width: Int
  ) extends DFMatchable:
    def codeString(using Printer): String = s"DFBits($width)"
  object DFBits:
    def apply[W <: Int](width: Inlined.Int[W]): DFBits[W] = new DFBits[W](width)
    @targetName("applyNoArg")
    def apply[W <: Int with Singleton](using ValueOf[W]): DFBits[W] =
      new DFBits[W](valueOf[W])
  /////////////////////////////////////////////////////////////////////////////

  /////////////////////////////////////////////////////////////////////////////
  // DFEnum
  /////////////////////////////////////////////////////////////////////////////
  sealed trait DFEncoding extends scala.reflect.Enum:
    def calcWidth(entryCount: Int): Int
    def encode(idx: Int): BigInt
    val value: BigInt

  object DFEncoding:
    sealed trait Auto extends DFEncoding:
      val value: BigInt = encode(ordinal)
    abstract class Default extends StartAt(0)

    abstract class Grey extends Auto:
      final def calcWidth(entryCount: Int): Int =
        (entryCount - 1).bitsWidth(false)
      final def encode(idx: Int): BigInt = BigInt(idx ^ (idx >>> 1))

    abstract class StartAt[V <: Int with Singleton](value: V) extends Auto:
      final def calcWidth(entryCount: Int): Int =
        (entryCount - 1 + value).bitsWidth(false)
      final def encode(idx: Int): BigInt = BigInt(idx + value)
    object StartAt:
      def unapply(using Quotes)(
          tpe: quotes.reflect.TypeRepr
      ): Option[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        val encodingTpe = TypeRepr.of[StartAt[_]]
        if (tpe <:< encodingTpe)
          val applied =
            tpe.baseType(encodingTpe.typeSymbol).asInstanceOf[AppliedType]
          Some(applied.args.head)
        else None

    abstract class OneHot extends Auto:
      final def calcWidth(entryCount: Int): Int = entryCount
      final def encode(idx: Int): BigInt = BigInt(1) << idx

    abstract class Manual[W <: Int with Singleton](val width: W)
        extends DFEncoding:
      final def calcWidth(entryCount: Int): Int = width
      final def encode(idx: Int): BigInt = value
    object Manual:
      def unapply(using Quotes)(
          tpe: quotes.reflect.TypeRepr
      ): Option[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        val encodingTpe = TypeRepr.of[Manual[_]]
        if (tpe <:< encodingTpe)
          val applied =
            tpe.baseType(encodingTpe.typeSymbol).asInstanceOf[AppliedType]
          Some(applied.args.head)
        else None

  final case class DFEnum[C <: AnyRef, E](
      val name: String,
      protected[DFType] val width: Int,
      val entries: ListMap[String, BigInt]
  ) extends DFMatchable:
    def codeString(using Printer): String = name
  object DFEnum:
    def unapply(using Quotes)(
        tpe: quotes.reflect.TypeRepr
    ): Option[List[quotes.reflect.TypeRepr]] =
      import quotes.reflect.*
      val enumTpe = TypeRepr.of[scala.reflect.Enum]
      val sym = tpe.termSymbol
      if (sym.companionClass.flags.is(Flags.Enum))
        Some(
          sym.declaredFields.view
            .map(f => tpe.memberType(f))
            .filter(_ <:< enumTpe)
            .toList
        )
      else None
    def apply[C <: AnyRef, E](enumCompanion: C): DFEnum[C, E] =
      val enumClass = classOf[scala.reflect.Enum]
      val enumCompanionCls = enumCompanion.getClass
      val fieldsAsPairs = for (
        field <- enumCompanionCls.getDeclaredFields
        if enumClass.isAssignableFrom(field.getType)
      ) yield {
        field.setAccessible(true)
        (field.getName, field.get(enumCompanion).asInstanceOf[DFEncoding])
      }
      val name = enumCompanionCls.getSimpleName.replace("$", "")
      val width = fieldsAsPairs.head._2.calcWidth(fieldsAsPairs.size)
      val entryPairs = fieldsAsPairs.zipWithIndex.map {
        case ((name, entry), idx) => (name, entry.value)
      }
      DFEnum[C, E](name, width, ListMap(entryPairs: _*))
  /////////////////////////////////////////////////////////////////////////////

  /////////////////////////////////////////////////////////////////////////////
  // DFVector
  /////////////////////////////////////////////////////////////////////////////
  final case class DFVector[T <: DFType, D <: NonEmptyTuple](
      cellType: T,
      cellDim: D
  ) extends DFFlattenable:
    protected[DFType] val width: Int =
      cellType.width * cellDim.toList.asInstanceOf[List[Int]].reduce(_ * _)
    def codeString(using Printer): String =
      s"${cellType.codeString}.X${cellDim.toList.mkString("(", ", ", ")")}"
  /////////////////////////////////////////////////////////////////////////////

  /////////////////////////////////////////////////////////////////////////////
  // DFOpaque
  /////////////////////////////////////////////////////////////////////////////
  abstract class DFOpaque[T <: DFType](
      actualType: T
  )(using meta: MetaContext)
      extends DFFlattenable:
    final protected[DFType] val width: Int = actualType.width
    final def codeString(using Printer): String = meta.name
  object DFOpaque:
    transparent inline def apply[T <: DFType](actualType: T)(using
        MetaContext
    ): DFOpaque[_] =
      new DFOpaque[T](actualType) {}
  /////////////////////////////////////////////////////////////////////////////

  /////////////////////////////////////////////////////////////////////////////
  // DFFields are used for either struct or enumerations (tagged unions)
  /////////////////////////////////////////////////////////////////////////////
  abstract class DFFields(using meta: MetaContext)
      extends Product,
        Serializable:
    final private val all =
      mutable.ListBuffer.empty[DFField[_ <: DFType]]
    final protected[DFType] lazy val width: Int = all.map(_.dfType.width).sum
    final lazy val getFields = all.toList
    final val name: String = meta.clsNameOpt.get
    protected sealed trait FIELD
    protected object FIELD extends FIELD
    extension [T <: Supported](t: T)(using tc: TC[T])
      def <>(FIELD: FIELD)(using MetaContext): DFField[tc.Type] =
        val dfType = tc(t)
        val field = DFField(dfType)
        all += field
        field
  final case class DFField[Type <: DFType](dfType: Type)(using
      meta: MetaContext
  ):
    val name: String = meta.name
  /////////////////////////////////////////////////////////////////////////////

  /////////////////////////////////////////////////////////////////////////////
  // DFUnion
  /////////////////////////////////////////////////////////////////////////////
  final case class DFUnion[F <: DFFields](fieldsSet: Set[DFType])
      extends DFType:
    protected[DFType] val width: Int = fieldsSet.head.width
    def codeString(using Printer): String =
      fieldsSet.map(_.codeString).mkString(" | ")
  object DFUnion:
    trait Able[T]:
      type F <: DFFields
      def apply(t: T): DFUnion[F]
    object Able:
      transparent inline given fromFields[F0 <: DFFields]: Able[F0] =
        new Able[F0]:
          type F = F0
          def apply(t: F0): DFUnion[F] = DFUnion[F](???)
      transparent inline given fromUnion[F0 <: DFFields]: Able[DFUnion[F0]] =
        new Able[DFUnion[F0]]:
          type F = F0
          def apply(t: DFUnion[F0]): DFUnion[F] = t
    object Ops:
      extension [L](lhs: L)(using l: Able[L])
        def |[R](rhs: R)(using r: Able[R]): DFUnion[l.F | r.F] = ???
  /////////////////////////////////////////////////////////////////////////////

  /////////////////////////////////////////////////////////////////////////////
  // DFStruct
  /////////////////////////////////////////////////////////////////////////////
  final case class DFStruct[F <: DFFields](fields: F) extends DFFlattenable:
    protected[DFType] val width: Int = fields.width
    def codeString(using Printer): String = fields.name
  /////////////////////////////////////////////////////////////////////////////

  /////////////////////////////////////////////////////////////////////////////

  /////////////////////////////////////////////////////////////////////////////
  // DFTuple
  /////////////////////////////////////////////////////////////////////////////
  final case class DFTuple[T <: AnyRef](t: T)
      extends DFMatchable,
        DFFlattenable:
    val dfTypeList: List[DFType] =
      t.asInstanceOf[NonEmptyTuple]
        .toList
        //TODO: Hack due to https://github.com/lampepfl/dotty/issues/12721
        .asInstanceOf[List[AnyRef]]
        .map(DFType.apply)
    protected[DFType] val width: Int = dfTypeList.view.map(_.width).sum
    def codeString(using Printer): String =
      dfTypeList.view.map(_.codeString).mkString("(", ", ", ")")
/////////////////////////////////////////////////////////////////////////////