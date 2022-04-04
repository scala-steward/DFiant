package StagesSpec

import DFiant.*
import DFiant.compiler.ir.{ClkCfg, RstCfg}
import DFiant.compiler.stages.dropRegsWires
// scalafmt: { align.tokens = [{code = "<>"}, {code = "="}, {code = "=>"}, {code = ":="}]}

class DropRegsWiresSpec extends StageSpec:
  test("Basic wires and reg") {
    class ID extends RTDesign:
      val x  = DFSInt(16) <> IN
      val y  = DFSInt(16) <> OUT
      val w1 = DFSInt(16) <> WIRE
      val w2 = DFSInt(16) <> WIRE
      val r1 = DFSInt(16) <> REG init 0
      w1     := x
      w1     := w1 + 1
      w2     := x
      r1.din := w2
      y      := w1 + r1
    val id = (new ID).dropRegsWires
    assertCodeString(
      id,
      """|class ID extends EDDesign:
         |  val clk = DFBit <> IN
         |  val rst = DFBit <> IN
         |  val x = DFSInt(16) <> IN
         |  val y = DFSInt(16) <> OUT
         |  val r1 = DFSInt(16) <> VAR init sd"16'0"
         |  val r1_din = DFSInt(16) <> VAR
         |  always.all {
         |    val w1 = DFSInt(16) <> VAR
         |    val w2 = DFSInt(16) <> VAR
         |    w1 := x
         |    w1 := w1 + sd"2'1"
         |    w2 := x
         |    r1_din :== w2
         |    y :== w1 + r1
         |  }
         |  always(clk) {
         |    if (clk.rising)
         |      if (rst == 1) r1 :== sd"16'0"
         |      else r1 :== r1_din
         |  }
         |end ID
         |""".stripMargin
    )
  }
  test("Basic Hierarchy") {
    class ID extends RTDesign:
      val x = DFSInt(16) <> IN
      val y = DFSInt(16) <> OUT
      val r = DFSInt(16) <> REG
      r.din := x
      y     := r

    class IDTop extends RTDesign:
      val x    = DFSInt(16) <> IN
      val y    = DFSInt(16) <> OUT
      val id_x = DFSInt(16) <> WIRE
      val id_y = DFSInt(16) <> WIRE
      val id = new ID:
        this.x <> id_x
        this.y <> id_y
      id_x := x
      y    := id_y
    end IDTop
    val id = (new IDTop).printCodeString.dropRegsWires
    assertCodeString(
      id,
      """|class ID extends EDDesign:
         |  val x = DFSInt(16) <> IN
         |  val y = DFSInt(16) <> OUT
         |  always.all {
         |    y :== x
         |  }
         |end ID
         |
         |class IDTop extends EDDesign:
         |  val clk = DFBit <> IN
         |  val rst = DFBit <> IN
         |  val x = DFSInt(16) <> IN
         |  val y = DFSInt(16) <> OUT
         |  val r1 = DFSInt(16) <> VAR init sd"16'0"
         |  val r2 = DFSInt(16) <> VAR init sd"16'5"
         |  val r1_din = DFSInt(16) <> VAR
         |  val r2_din = DFSInt(16) <> VAR
         |  always.all {
         |    val w1 = DFSInt(16) <> VAR
         |    val w2 = DFSInt(16) <> VAR
         |    val r2_din_v = DFSInt(16) <> VAR
         |    w1 := x
         |    w1 := w1 + sd"2'1"
         |    w2 := x
         |    r1_din :== w2
         |    r2_din_v := 0
         |    r2_din_v := r2_din_v + 1
         |    y :== w1 + r1
         |    r2_din :== r2_din_v
         |  }
         |  always(clk) {
         |    if (clk.rising)
         |      if (rst == 1) 
         |        r1 :== sd"16'0"
         |        r2 :== sd"16'5"
         |      else 
         |        r1 :== r1_din
         |        r2 :== r2_din
         |  }
         |end IDTop
         |""".stripMargin
    )
  }
  test("Rising clk, Async Reset") {
    val clkCfg = ClkCfg("clk", ClkCfg.Edge.Rising)
    val rstCfg = RstCfg("rst", RstCfg.Mode.Async, RstCfg.Active.High)
    class ID extends RTDesign(clkCfg, rstCfg):
      val x  = DFSInt(16) <> IN
      val r1 = DFSInt(16) <> REG init 0
      r1.din := x
    val id = (new ID).dropRegsWires
    assertCodeString(
      id,
      """|class ID extends EDDesign:
         |  val clk = DFBit <> IN
         |  val rst = DFBit <> IN
         |  val x = DFSInt(16) <> IN
         |  val r1 = DFSInt(16) <> VAR init sd"16'0"
         |  val r1_din = DFSInt(16) <> VAR
         |  always.all {
         |    r1_din :== x
         |  }
         |  always(clk, rst) {
         |    if (rst == 1) r1 :== sd"16'0"
         |    else if (clk.rising) r1 :== r1_din
         |  }
         |end ID
         |""".stripMargin
    )
  }
  test("Falling clk, no Reset") {
    val clkCfg = ClkCfg("clk", ClkCfg.Edge.Falling)
    val rstCfg = None
    class ID extends RTDesign(clkCfg, rstCfg):
      val x  = DFSInt(16) <> IN
      val r1 = DFSInt(16) <> REG init 0
      r1.din := x
    val id = (new ID).dropRegsWires
    assertCodeString(
      id,
      """|class ID extends EDDesign:
         |  val clk = DFBit <> IN
         |  val x = DFSInt(16) <> IN
         |  val r1 = DFSInt(16) <> VAR init sd"16'0"
         |  val r1_din = DFSInt(16) <> VAR
         |  always.all {
         |    r1_din :== x
         |  }
         |  always(clk) {
         |    if (clk.falling) r1 :== r1_din
         |  }
         |end ID
         |""".stripMargin
    )
  }
  test("Rising clk, Sync Reset & Active-low, names change") {
    val clkCfg = ClkCfg("clk_p", ClkCfg.Edge.Rising)
    val rstCfg = RstCfg("rst_n", RstCfg.Mode.Async, RstCfg.Active.Low)
    class ID extends RTDesign(clkCfg, rstCfg):
      val x  = DFSInt(16) <> IN
      val r1 = DFSInt(16) <> REG init 0
      r1.din := x
    val id = (new ID).dropRegsWires
    assertCodeString(
      id,
      """|class ID extends EDDesign:
         |  val clk_p = DFBit <> IN
         |  val rst_n = DFBit <> IN
         |  val x = DFSInt(16) <> IN
         |  val r1 = DFSInt(16) <> VAR init sd"16'0"
         |  val r1_din = DFSInt(16) <> VAR
         |  always.all {
         |    r1_din :== x
         |  }
         |  always(clk_p, rst_n) {
         |    if (rst_n == 0) r1 :== sd"16'0"
         |    else if (clk_p.rising) r1 :== r1_din
         |  }
         |end ID
         |""".stripMargin
    )
  }
end DropRegsWiresSpec
