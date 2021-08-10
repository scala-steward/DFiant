import DFiant.*

class Foo(using DFC) extends DFDesign:
  val x = DFBits(8) <> IN
  val y = (DFBits(8), DFBit) <> OUT
  y := (x, 1)

object Bla extends App:
  val top = new Foo
  top.printCodeString()
//  summon[core.DFToken.TC[DFBits[6], DFBits.Token[8]]]
