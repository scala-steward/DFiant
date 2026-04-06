package dfhdl.plugin

import dotty.tools.dotc._

import plugins._

import core._
import Contexts._
import Symbols._
import Flags._
import SymDenotations._

import Decorators._
import ast.Trees._
import ast.untpd
import StdNames.nme
import Names._
import Constants.Constant
import Types._
import scala.language.implicitConversions
import collection.mutable
import annotation.tailrec
import reporting.*

// not used, but can be potentially useful for modified the reported compiler errors
class CustomReporter(
    val orig: Reporter
) extends Reporter:
  override def flush()(using ctx: Context): Unit = orig.flush()
  override def doReport(dia: Diagnostic)(using ctx: Context): Unit =
    val updatedMsg = dia.msg.toString
    val updatedDia = Diagnostic(dia.msg.mapMsg(x => updatedMsg), dia.pos, dia.level)
    orig.doReport(updatedDia)
  end doReport
end CustomReporter

/** This is a pre-typer phase that does very minor things:
  *   - change infix operator precedence of type signature: `a X b <> c` to be `(a X b) <> c`
  *   - change infix operator precedence of terms: `a <> b op c` to be `a <> (b op c)` and `a op b
  *     <> c` to be `(a op b) <> c`, where op is `|`, `||`, `&`, `&&`, `^`, or a comparison operator
  *   - change infix operator precedence of terms: `a := b match {...}` to be `a := (b match {...})`
  *     and `a <> b match {...}` to be `a <> (b match {...})`
  *   - change process{} to process.forever{}
  */
class PreTyperPhase(setting: Setting) extends CommonPhase:
  import untpd.*

  val phaseName = "PreTyper"

  override val runsAfter = Set("parser")
  override val runsBefore = Set("typer")
  private var debugFlag = false
  // override to prevent from running redundant MiniPhase transformation
  // that can cause compiler errors
  override def run(using Context): Unit = {}

  def debug2(str: => Any*): Unit =
    if (debugFlag) println(str.mkString(", "))

  val opSet = Set("|", "||", "&", "&&", "^", "<<", ">>", "==", "!=", "<", ">", "<=", ">=")
  private val `fix<>andOpPrecedence` = new UntypedTreeMap:
    object InfixOpArgsChange:
      def unapply(tree: InfixOp)(using Context): Option[(Tree, Ident, Tree)] =
        tree match
          case InfixOp(InfixOpArgsChange(a, Ident(conn), b), Ident(op), c)
              if opSet.contains(op.toString) =>
            Some(a, Ident(conn), InfixOp(b, Ident(op), c))
          case InfixOp(a, Ident(op), InfixOpArgsChange(b, Ident(conn), c))
              if opSet.contains(op.toString) =>
            Some(InfixOp(a, Ident(op), b), Ident(conn), c)
          case InfixOp(a, Ident(op), InfixOp(b, Ident(conn), c))
              if conn.toString == "<>" && opSet.contains(op.toString) =>
            Some(InfixOp(a, Ident(op), b), Ident(conn), c)
          case InfixOp(InfixOp(a, Ident(conn), b), Ident(op), c)
              if conn.toString == "<>" && opSet.contains(op.toString) =>
            Some(a, Ident(conn), InfixOp(b, Ident(op), c))
          case _ =>
            None
    end InfixOpArgsChange
    object InfixOpChange:
      def unapply(tree: InfixOp)(using Context): Option[InfixOp] =
        tree match
          case InfixOpArgsChange(a, Ident(conn), b) => Some(InfixOp(a, Ident(conn), Parens(b)))
          case _                                    =>
            None
    end InfixOpChange
    object MatchAssignOpChange:
      def unapply(tree: Match)(using Context): Option[InfixOp] =
        tree match
          case Match(InfixOp(a, Ident(op), b), cases)
              if op.toString == ":=" || op.toString == "<>" =>
            Some(InfixOp(a, Ident(op), Parens(Match(b, cases))))
          case _ =>
            None
    object ProcessChange:
      def unapply(tree: Tree)(using Context): Option[Tree] =
        tree match
          case Apply(Ident(process), List(ofTree)) if process.toString == "process" =>
            Some(Apply(Select(Ident(process), "forever".toTermName), List(ofTree)))
          case ValDef(name, tpt, ProcessChange(rhs)) =>
            Some(ValDef(name, tpt, rhs))
          case _ => None
    override def transformBlock(blk: Block)(using Context): Block =
      super.transformBlock(blk) match
        // a connection/assignment could be in return expression position of a Unit-typed block
        case Block(stats, InfixOpChange(expr))       => Block(stats, expr)
        case Block(stats, MatchAssignOpChange(expr)) => Block(stats, expr)
        case Block(stats, ProcessChange(expr))       => Block(stats, expr)
        case blk                                     => blk
    override def transformStats(trees: List[Tree], exprOwner: Symbol)(using Context): List[Tree] =
      super.transformStats(trees, exprOwner).map:
        // only handling pure statements that begin as an infix
        case InfixOpChange(tree)       => tree
        case MatchAssignOpChange(tree) => tree
        // change process{} to process.forever{}
        case ProcessChange(tree) => tree
        case tree                => tree
    override def transform(tree: Tree)(using Context): Tree =
      super.transform(tree) match
        // a connection could be in return position of a DFHDL Unit definition (if no block is used)
        case tree @ DefDef(preRhs = InfixOpChange(rhs)) =>
          cpy.DefDef(tree)(rhs = rhs)
        case t => t
      end match
    end transform

  private val `fixXand<>Precedence` = new UntypedTreeMap:
    object InfixOpChange:
      def unapply(tree: InfixOp)(using Context): Option[InfixOp] =
        tree match
          case InfixOp(a, Ident(x), InfixOp(b, Ident(conn), c))
              if x.toString == "X" && conn.toString == "<>" =>
            Some(InfixOp(Parens(InfixOp(a, Ident(x), b)), Ident(conn), c))
          case _ => None
    object FullSelectGivenName:
      def unapply(tree: Select)(using Context): Option[String] =
        tree match
          case Select(Ident(options), name) if options.toString == "options" =>
            Some(s"options_${name}")
          case Select(FullSelectGivenName(prev), name) => Some(s"${prev}_$name")
          case _                                       => None
    override def transform(tree: Tree)(using Context): Tree =
      super.transform(tree) match
        case tree @ InfixOpChange(rhs) => rhs
        // workaround https://github.com/scala/scala3/issues/21406
        case tree @ ValDef(name, select: Select, _) if name.isEmpty && tree.mods.is(Given) =>
          select match
            case FullSelectGivenName(updateName) => cpy.ValDef(tree)(name = updateName.toTermName)
            case _                               => tree
        case t =>
          t
      end match
    end transform
  object DFType:
    def unapply(arg: Type)(using Context): Option[(String, List[Type])] =
      arg.simple match
        case AppliedType(dfTypeCore, List(n, argsTp))
            if dfTypeCore.typeSymbol == requiredClass("dfhdl.core.DFType") =>
          val nameStr = n.typeSymbol.name.toString
          argsTp match
            case AppliedType(_, args) => Some(nameStr, args)
            case _                    => Some(nameStr, Nil)
        case _ => None
  end DFType
  object DFBool:
    def unapply(arg: Type)(using Context): Boolean =
      arg match
        case DFType("DFBool$", Nil) => true
        case _                      => false
  object DFBit:
    def unapply(arg: Type)(using Context): Boolean =
      arg match
        case DFType("DFBit$", Nil) => true
        case _                     => false
  object DFBits:
    def unapply(arg: Type)(using Context): Option[Type] =
      arg match
        case DFType("DFBits", w :: Nil) => Some(w)
        case _                          => None
  object DFDecimal:
    def unapply(arg: Type)(using Context): Option[(Type, Type, Type)] =
      arg match
        // ignoring the fourth native argument, since it's not needed for matching
        case DFType("DFDecimal", s :: w :: f :: _ :: Nil) => Some(s, w, f)
        case _                                            => None
  object DFXInt:
    def unapply(arg: Type)(using Context): Option[(Boolean, Type)] =
      arg match
        case DFDecimal(
              ConstantType(Constant(sign: Boolean)),
              widthTpe,
              ConstantType(Constant(fractionWidth: Int))
            ) if fractionWidth == 0 =>
          Some(sign, widthTpe)
        case _ => None
  object DFUInt:
    def unapply(arg: Type)(using Context): Option[Type] =
      arg match
        case DFXInt(sign, widthTpe) if !sign => Some(widthTpe)
        case _                               => None
  object DFSInt:
    def unapply(arg: Type)(using Context): Option[Type] =
      arg match
        case DFXInt(sign, widthTpe) if sign => Some(widthTpe)
        case _                              => None
  object DFEnum:
    def unapply(arg: Type)(using Context): Option[Type] =
      arg match
        case DFType("DFEnum", e :: Nil) => Some(e)
        case _                          => None
  object DFStruct:
    def unapply(arg: Type)(using Context): Option[Type] =
      arg match
        case DFType("DFStruct", t :: Nil) => Some(t)
        case _                            => None

  object DFVal:
    private def stripAndType(tpeOpt: Option[Type])(using Context): Option[Type] =
      tpeOpt.map(tpe =>
        tpe.simple match
          case AndType(t1, _) => t1
          case _              => tpe
      )
    def unapply(arg: Type)(using Context): Option[Type] =
      val dfValClsRef = requiredClassRef("dfhdl.core.DFVal")
      val ret = arg.simple match
        case AppliedType(t, List(dfType, _)) if t <:< dfValClsRef =>
          Some(dfType)
        case AppliedType(t, List(arg, mod))
            if t.typeSymbol.name.toString == "<>" &&
              (mod <:< requiredClassRef("dfhdl.VAL") || mod <:< requiredClassRef("dfhdl.DFRET")) =>
          arg match
            case dfType @ DFType(_, _) => Some(dfType)
            case _                     => None
        case _ =>
          None
      stripAndType(ret)
    end unapply
  end DFVal

  // not used, but can be potentially useful for modified the reported compiler errors
  override def initContext(ctx: FreshContext): Unit =
    import dotty.tools.dotc.printing.*
    import dotty.tools.dotc.printing.Texts.Text
    def foo(ctx: Context): Printer =
      new RefinedPrinter(ctx):
        override def toText(tp: Type): Text =
          tp match
            case DFVal(dfType) =>
              val dfTypeText: Text = dfType match
                case DFBool()           => "Boolean"
                case DFBit()            => "Bit"
                case DFBits(w)          => s"Bits[${w.show}]"
                case DFUInt(w)          => s"UInt[${w.show}]"
                case DFSInt(w)          => s"SInt[${w.show}]"
                case DFDecimal(s, w, f) => s"Decimal[${s.show}, ${w.show}, ${f.show}]"
                case DFEnum(e)          => s"Enum[${e.show}]"
                case DFStruct(t)        => s"Struct[${t.show}]"
                case _                  => super.toText(tp)
              dfTypeText ~ " <> Val"
            case _ => super.toText(tp)
    ctx.setPrinterFn(foo)
    val typerState = ctx.typerState.setReporter(new CustomReporter(ctx.reporter))
    ctx.setTyperState(typerState)
  end initContext

  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
    val parsed = super.runOn(units)
    parsed.foreach { cu =>
      debugFlag = cu.source.file.path.contains("Playground.scala")
      cu.untpdTree = `fix<>andOpPrecedence`.transform(cu.untpdTree)
      cu.untpdTree = `fixXand<>Precedence`.transform(cu.untpdTree)
    }
    parsed
  end runOn
end PreTyperPhase
