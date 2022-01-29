package StagesSpec

import DFiant.*
import DFiant.compiler.stages.{sanityCheck, getCodeString}
// scalafmt: { align.tokens = [{code = "<>"}, {code = "="}, {code = "=>"}, {code = ":="}]}

class PrintCodeStringSpec extends StageSpec:
  class ID(using DFC) extends DFDesign:
    val x = DFSInt(16) <> IN
    val y = DFSInt(16) <> OUT
    y := x

  class IDGen[T <: DFType](dfType: T)(using DFC) extends DFDesign:
    val x = dfType <> IN
    val y = dfType <> OUT
    y := x

  class IDTop(using DFC) extends DFDesign:
    val x   = DFSInt(16) <> IN
    val y   = DFSInt(16) <> OUT
    val id1 = new ID
    val id2 = new ID
    id1.x <> x
    id1.y <> id2.x
    id2.y <> y

  class IDTopVia(using DFC) extends DFDesign:
    self =>
    val x     = DFSInt(16) <> IN
    val y     = DFSInt(16) <> OUT
    val id1_x = DFSInt(16) <> VAR
    val id1_y = DFSInt(16) <> VAR
    val id2_x = DFSInt(16) <> VAR
    val id2_y = DFSInt(16) <> VAR
    val id1 = new ID:
      this.x <> id1_x
      this.y <> id1_y
    val id2 = new ID:
      this.x <> id2_x
      this.y <> id2_y
    x     <> id1_x
    id1_y <> id2_x
    y     <> id2_y
  end IDTopVia

  test("Basic ID design") {
    val id = (new ID).getCodeString
    assertNoDiff(
      id,
      """|class ID(using DFC) extends DFDesign:
         |  val x = DFSInt(16) <> IN
         |  val y = DFSInt(16) <> OUT
         |  y := x
         |end ID
         |""".stripMargin
    )
  }
  test("Generic ID design") {
    val id = (new IDGen(DFBits(8))).getCodeString
    assertNoDiff(
      id,
      """|class IDGen(using DFC) extends DFDesign:
         |  val x = DFBits(8) <> IN
         |  val y = DFBits(8) <> OUT
         |  y := x
         |end IDGen
         |""".stripMargin
    )
    val id2 = (new IDGen((DFBits(4), DFSInt(4)))).getCodeString
    assertNoDiff(
      id2,
      """|class IDGen(using DFC) extends DFDesign:
         |  val x = (DFBits(4), DFSInt(4)) <> IN
         |  val y = (DFBits(4), DFSInt(4)) <> OUT
         |  y := x
         |end IDGen
         |""".stripMargin
    )
  }
  test("Basic ID design hierarchy") {
    val id = (new IDTop).getCodeString
    assertNoDiff(
      id,
      """|class ID(using DFC) extends DFDesign:
         |  val x = DFSInt(16) <> IN
         |  val y = DFSInt(16) <> OUT
         |  y := x
         |end ID
         |
         |class IDTop(using DFC) extends DFDesign:
         |  val x = DFSInt(16) <> IN
         |  val y = DFSInt(16) <> OUT
         |  val id1 = new ID
         |  val id2 = new ID
         |  id1.x <>/*<--*/ x
         |  id1.y <>/*-->*/ id2.x
         |  id2.y <>/*-->*/ y
         |end IDTop
         |""".stripMargin
    )
  }
  test("Via-connection ID design hierarchy") {
    val id = (new IDTopVia).getCodeString
    assertNoDiff(
      id,
      """|class ID(using DFC) extends DFDesign:
         |  val x = DFSInt(16) <> IN
         |  val y = DFSInt(16) <> OUT
         |  y := x
         |end ID
         |
         |class IDTopVia(using DFC) extends DFDesign:
         |  val x = DFSInt(16) <> IN
         |  val y = DFSInt(16) <> OUT
         |  val id1_x = DFSInt(16) <> VAR
         |  val id1_y = DFSInt(16) <> VAR
         |  val id2_x = DFSInt(16) <> VAR
         |  val id2_y = DFSInt(16) <> VAR
         |  val id1 = new ID:
         |    this.x <>/*<--*/ id1_x
         |    this.y <>/*-->*/ id1_y
         |  val id2 = new ID:
         |    this.x <>/*<--*/ id2_x
         |    this.y <>/*-->*/ id2_y
         |  x <>/*-->*/ id1_x
         |  id1_y <>/*-->*/ id2_x
         |  y <>/*<--*/ id2_y
         |end IDTopVia
         |""".stripMargin
    )
  }
  test("Design names affect named dataflow types") {
    object ID extends DFOpaque(DFBit)
    class ID(using DFC) extends DFDesign:
      val x = DFSInt(16) <> IN
      val y = DFSInt(16) <> OUT
      val z = ID         <> VAR init 0.as(ID)
      y := x
    val id = (new ID).getCodeString
    assertNoDiff(
      id,
      """|class ID(using DFC) extends DFDesign:
         |  object ID_0 extends DFOpaque(DFBit)
         |
         |  val x = DFSInt(16) <> IN
         |  val y = DFSInt(16) <> OUT
         |  val z = ID_0 <> VAR init 0.as(ID_0)
         |  y := x
         |end ID
         |""".stripMargin
    )
  }
end PrintCodeStringSpec