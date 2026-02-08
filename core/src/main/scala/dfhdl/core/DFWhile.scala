package dfhdl.core
import dfhdl.compiler.ir
import dfhdl.internals.*
import scala.annotation.implicitNotFound
import scala.reflect.ClassTag

object DFWhile:
  object Block:
    def apply(guard: DFValOf[DFBoolOrBit])(using DFC): DFOwnerAny =
      val block = ir.DFLoop.DFWhileBlock(
        guardRef = guard.asIR.refTW[ir.DFLoop.DFWhileBlock],
        ownerRef = dfc.owner.ref,
        meta = dfc.getMeta,
        tags = dfc.tags
      )
      block.addMember.asFE
  end Block
  def plugin(guard: DFValOf[DFBoolOrBit])(run: => Unit)(using DFC): Unit =
    val block = Block(guard)
    dfc.enterOwner(block)
    run
    dfc.exitOwner()
end DFWhile

protected[dfhdl] object LoopOps:
  private def loopTag[CT <: ir.DFTag: ClassTag](tag: CT)(using DFC): Unit =
    import dfc.getSet
    var ownerIR = dfc.owner.asIR
    var stop = false
    var lineEnd = -1
    while (!stop)
      ownerIR match
        case cb: ir.DFConditional.Block => ownerIR = cb.getOwner
        case lb: ir.DFLoop.Block        =>
          if (lineEnd == -1)
            lineEnd = lb.meta.position.lineEnd
          else if (lineEnd != lb.meta.position.lineEnd)
            stop = true
          if (!stop)
            ownerIR.setTags(_.tag(tag))
            ownerIR = lb.getOwner
        case _ => stop = true
  end loopTag

  // to be used inside an RT loop to indicate that the loop is combinational
  def COMB_LOOP(using
      dfc: DFC,
      @implicitNotFound(
        "`COMB_LOOP` is only allowed under register-transfer (RT) domains."
      ) rt: DomainType.RT
  ): Unit = loopTag(ir.CombinationalTag)

  // to be used inside an RT loop to indicate that the loop should fall through to the next step if the guard is false
  def FALL_THROUGH(using
      dfc: DFC,
      @implicitNotFound(
        "`FALL_THROUGH` is only allowed under register-transfer (RT) domains."
      ) rt: DomainType.RT
  ): Unit = loopTag(ir.FallThroughTag)
end LoopOps
