package dfhdl.plugin

import dotty.tools.dotc.*
import plugins.*
import core.*
import Contexts.*
import Symbols.*
import Flags.*
import SymDenotations.*
import Decorators.*
import ast.Trees.*
import ast.{tpd, untpd, TreeTypeMap}
import StdNames.nme
import Names.{Designator, *}
import Constants.Constant
import Types.*

import scala.language.implicitConversions
import scala.compiletime.uninitialized
import collection.mutable
import annotation.tailrec
import dotty.tools.dotc.ast.Trees.Alternative

/*
  This phase overrides the `__dfc` def of DFHDL classes to propagate the DFC
  from an encapsulating class or def to its DFHDL class instance. If a class
  is instantiated regularly the instance is transformed into an anonymous
  class instance with the override, otherwise all is required is to add the
  additional override to an existing anonymous DFHDL class instance.
 */
class MetaContextPlacerPhase(setting: Setting) extends CommonPhase:
  import tpd._

  val phaseName = "MetaContextPlacer"

  override val runsAfter = Set("typer")
  override val runsBefore = Set("FixInterpDFValPhase")
  // override val debugFilter: String => Boolean = _.contains("PluginSpec.scala")
  var dfcArgStack = List.empty[Tree]
  var emptyDFCSym: TermSymbol = uninitialized
  var dfcTpe: Type = uninitialized
  var dfSpecTpe: Type = uninitialized
  var hasClsMetaArgsTpe: TypeRef = uninitialized
  var clsMetaArgsTpe: TypeRef = uninitialized

  extension (tree: TypeDef)
    def hasDFC(using Context): Boolean =
      (tree.tpe <:< hasDFCTpe) // && (dfSpecTpe == NoType || !(tree.tpe <:< dfSpecTpe))
  override def prepareForTypeDef(tree: TypeDef)(using Context): Context =
    val sym = tree.symbol
    tree.rhs match
      case template: Template if tree.hasDFC =>
        if (sym.is(Final) && !sym.isAnonymousClass)
          report.error("DFHDL classes cannot be final.", tree.srcPos)
        dfcArgStack = This(sym.asClass).select("dfc".toTermName) :: dfcArgStack
      case _ =>
    ctx

  private def clsMetaArgsOverrideDef(owner: Symbol, clsMetaArgsTree: Tree)(using Context): Tree =
    val sym =
      newSymbol(
        owner,
        "__clsMetaArgs".toTermName,
        Override | Protected | Method | Touched,
        clsMetaArgsTpe
      )
    DefDef(
      sym,
      clsMetaArgsTree
    )
  end clsMetaArgsOverrideDef

  private def clsMetaArgsOverrideDef(owner: Symbol)(using Context): Tree =
    clsMetaArgsOverrideDef(owner, ref(requiredMethod("dfhdl.internals.ClsMetaArgs.empty")))

  override def transformTypeDef(tree: TypeDef)(using Context): TypeDef =
    val sym = tree.symbol
    tree.rhs match
      case template: Template =>
        if (tree.hasDFC)
          dfcArgStack = dfcArgStack.drop(1)
        val clsTpe = tree.tpe
        val clsSym = clsTpe.classSymbol.asClass
        if (clsTpe <:< hasClsMetaArgsTpe && !clsSym.isAnonymousClass)
          val args =
            template.constr.paramss.flatten.collect { case v: ValDef =>
              mkTuple(
                List(Literal(Constant(v.name.toString)), ref(v.symbol))
              )
            }

          val listMapTree =
            if (args.isEmpty)
              ref(requiredMethod("scala.collection.immutable.ListMap.empty"))
                .appliedToTypes(List(defn.StringType, defn.AnyType))
            else
              ref(requiredModule("scala.collection.immutable.ListMap")).select(nme.apply)
                .appliedToTypes(List(defn.StringType, defn.AnyType))
                .appliedToVarargs(
                  args,
                  TypeTree(
                    AppliedType(
                      requiredClassRef("scala.Tuple2"),
                      List(defn.StringType, defn.AnyType)
                    )
                  )
                )
          // TODO: can we use this instead of mutation via setClsNamePosTree?
          // val clsMetaArgsTree = New(
          //   clsMetaArgsTpe,
          //   List(
          //     Literal(Constant(tree.name.toString)),
          //     tree.positionTree,
          //     mkOptionString(clsSym.docString),
          //     mkList(clsSym.staticAnnotations.map(_.tree)),
          //     listMapTree
          //   )
          // )
          // val clsMetaArgsDefTree = clsMetaArgsOverrideDef(clsSym, clsMetaArgsTree)
          val setClsNamePosTree =
            This(clsSym.asClass)
              .select("setClsNamePos".toTermName)
              .appliedToArgs(
                List(
                  Literal(Constant(tree.name.toString)),
                  tree.positionTree,
                  mkOptionString(clsSym.docString),
                  mkList(clsSym.staticAnnotations.map(_.tree)),
                  listMapTree
                )
              )
          val newTemplate = cpy.Template(template)(body = setClsNamePosTree :: template.body)
          cpy.TypeDef(tree)(rhs = newTemplate)
        else tree
        end if
      case _ =>
        tree
    end match
  end transformTypeDef

  override def prepareForDefDef(tree: DefDef)(using Context): Context =
    tree match
      case ContextArg(arg) =>
        dfcArgStack = arg :: dfcArgStack
      case _ =>
    ctx

  override def transformDefDef(tree: DefDef)(using Context): DefDef =
    tree match
      case ContextArg(arg) =>
        dfcArgStack = dfcArgStack.drop(1)
      case _ =>
    tree

  private def dfcOverrideDef(owner: Symbol)(using Context): Tree =
    val sym =
      newSymbol(owner, "__dfc".toTermName, Override | Protected | Method | Touched, dfcTpe)
    val dfcArg = dfcArgStack.headOption.getOrElse(ref(emptyDFCSym))
    DefDef(sym, dfcArg)

  override def transformApply(tree: Apply)(using Context): Tree =
    val tpe = tree.tpe
    tree match
      case Apply(Select(New(Ident(n)), _), _) if n == StdNames.tpnme.ANON_CLASS => tree
      case _
          if (
            tree.fun.symbol.isClassConstructor && tpe.isParameterless && !ctx.owner.owner.isAnonymousClass &&
              !ctx.owner.isClassConstructor && tpe.typeConstructor <:< hasDFCTpe
          ) =>
        val cls = newNormalizedClassSymbol(
          ctx.owner,
          StdNames.tpnme.ANON_CLASS,
          Synthetic | Final,
          List(tpe),
          coord = tree.symbol.coord
        )
        val constr = newConstructor(cls, Synthetic, Nil, Nil).entered
        val encClass = ctx.owner.enclosingClass
        var valDefs: List[ValDef] = Nil
        // naming the arguments before extending the tree as as parent because
        // otherwise ownership and references need to change.
        def nameArgs(tree: Tree): Tree =
          tree match
            case Apply(fun, args) =>
              val updatedArgs = args.map { a =>
                val uniqueName = NameKinds.UniqueName.fresh(s"arg_plugin".toTermName)
                val valDef = SyntheticValDef(uniqueName, a)
                valDefs = valDef :: valDefs
                ref(valDef.symbol)
              }
              Apply(nameArgs(fun), updatedArgs)
            case _ => tree
        val parent = nameArgs(tree)
        val od = dfcOverrideDef(cls)
        val cdef = ClassDefWithParents(cls, DefDef(constr), List(parent), List(od))
        Block(
          valDefs.reverse :+ cdef,
          Typed(New(Ident(cdef.namedType)).select(constr).appliedToNone, TypeTree(tpe))
        )
      case _ => tree
    end match
  end transformApply
  override def transformBlock(tree: Block)(using Context): tpd.Tree =
    tree match
      case Block(
            List(td @ TypeDef(tn, template: Template)),
            Typed(apply @ Apply(fun, _), _)
          ) if tree.tpe.typeConstructor <:< hasDFCTpe =>
        val hasDFCOverride = template.body.exists {
          case dd: DefDef if dd.name.toString == "__dfc" => true
          case _                                         => false
        }
        if (hasDFCOverride) tree
        else
          val od = dfcOverrideDef(td.symbol)
          val updatedTemplate = cpy.Template(template)(body = od :: template.body)
          val updatedTypeDef = cpy.TypeDef(td)(rhs = updatedTemplate)
          cpy.Block(tree)(stats = List(updatedTypeDef), expr = tree.expr)
      case _ =>
        tree

  override def prepareForUnit(tree: Tree)(using Context): Context =
    super.prepareForUnit(tree)
    emptyDFCSym = requiredMethod("dfhdl.core.DFC.empty")
    dfcTpe = requiredClassRef("dfhdl.core.DFC")
    dfSpecTpe = requiredClassRef("dfhdl.DFSpec")
    hasClsMetaArgsTpe = requiredClassRef("dfhdl.internals.HasClsMetaArgs")
    clsMetaArgsTpe = requiredClassRef("dfhdl.internals.ClsMetaArgs")
    dfcArgStack = Nil
    ctx
end MetaContextPlacerPhase