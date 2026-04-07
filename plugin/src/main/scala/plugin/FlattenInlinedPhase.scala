package dfhdl.plugin

import dotty.tools.dotc.*
import plugins.*
import core.*
import Contexts.*
import ast.tpd
import ast.tpd.*

// This phase flattens deeply nested Inlined trees that result from chained
// transparent inline operations (e.g., a + b + c + d). The Scala 3 posttyper
// phase exhibits super-linear behavior on deeply nested Inlined nodes, so
// flattening them before posttyper runs dramatically reduces compile time.
class FlattenInlinedPhase(setting: Setting) extends PluginPhase:
  import tpd.*

  val phaseName = "FlattenInlined"
  override val runsAfter = Set("typer")
  override val runsBefore = Set("posttyper")

  private val treeMap = new TreeMap:
    override def transform(tree: Tree)(using Context): Tree =
      super.transform(tree) match
        case inlined @ Inlined(call, bindings, expansion) =>
          val (innerBindings, innerExpansion) = flattenInlined(expansion)
          if innerBindings.isEmpty then inlined
          else cpy.Inlined(inlined)(call, bindings ++ innerBindings, innerExpansion)
        case tree => tree

  private def flattenInlined(tree: Tree)(using Context): (List[MemberDef], Tree) =
    tree match
      case Inlined(_, bindings, expansion) =>
        val (innerBindings, innerExpansion) = flattenInlined(expansion)
        (bindings ++ innerBindings, innerExpansion)
      case Block(stats, expr) =>
        val (innerBindings, innerExpr) = flattenInlined(expr)
        if innerBindings.isEmpty then (Nil, tree)
        else
          val allStats = stats.map(_.asInstanceOf[MemberDef]) ++ innerBindings
          (Nil, cpy.Block(tree)(allStats.toList, innerExpr))
      case _ => (Nil, tree)

  override def transformUnit(tree: Tree)(using Context): Tree =
    treeMap.transform(tree)
end FlattenInlinedPhase
