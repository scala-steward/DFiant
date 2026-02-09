package StagesSpec

import dfhdl.*
import dfhdl.compiler.stages.dropRTProcess

class DropRTProcessSpec extends StageSpec():
  test("named FSM steps") {
    class Foo extends RTDesign:
      val x = Bit <> IN
      val y = Bit <> OUT.REG init 0
      val my_fsm = process:
        def S0: Step =
          y.din := 0
          if (x) NextStep else S0
        def S1: Step =
          def onEntry =
            y.din := 1
          if (x) S2 else FirstStep
        def S2: Step =
          def onExit =
            y.din := 0
          if (x) ThisStep else FirstStep
    end Foo
    val top = (new Foo).dropRTProcess
    assertCodeString(
      top,
      """|class Foo extends RTDesign:
         |  enum my_fsm_State(val value: UInt[2] <> CONST) extends Encoded.Manual(2):
         |    case S0 extends my_fsm_State(d"2'0")
         |    case S1 extends my_fsm_State(d"2'1")
         |    case S2 extends my_fsm_State(d"2'2")
         |
         |  val x = Bit <> IN
         |  val y = Bit <> OUT.REG init 0
         |  val my_fsm_state = my_fsm_State <> VAR.REG init my_fsm_State.S0
         |  my_fsm_state match
         |    case my_fsm_State.S0 =>
         |      y.din := 0
         |      if (x)
         |        y.din := 1
         |        my_fsm_state.din := my_fsm_State.S1
         |      else my_fsm_state.din := my_fsm_State.S0
         |      end if
         |    case my_fsm_State.S1 =>
         |      if (x) my_fsm_state.din := my_fsm_State.S2
         |      else my_fsm_state.din := my_fsm_State.S0
         |    case my_fsm_State.S2 =>
         |      if (x) my_fsm_state.din := my_fsm_State.S2
         |      else
         |        y.din := 0
         |        my_fsm_state.din := my_fsm_State.S0
         |      end if
         |  end match
         |end Foo""".stripMargin
    )
  }
  test("unnamed FSM steps") {
    class Foo extends RTDesign:
      val x = Bit <> IN
      val y = Bit <> OUT.REG init 0
      process:
        def S0: Step =
          y.din := 0
          if (x) S1 else S0
        def S1: Step =
          y.din := 1
          if (x) S2 else S0
        def S2: Step =
          y.din := 0
          if (x) S2 else S0
    end Foo
    val top = (new Foo).dropRTProcess
    assertCodeString(
      top,
      """|class Foo extends RTDesign:
         |  enum State(val value: UInt[2] <> CONST) extends Encoded.Manual(2):
         |    case S0 extends State(d"2'0")
         |    case S1 extends State(d"2'1")
         |    case S2 extends State(d"2'2")
         |
         |  val x = Bit <> IN
         |  val y = Bit <> OUT.REG init 0
         |  val state = State <> VAR.REG init State.S0
         |  state match
         |    case State.S0 =>
         |      y.din := 0
         |      if (x) state.din := State.S1
         |      else state.din := State.S0
         |    case State.S1 =>
         |      y.din := 1
         |      if (x) state.din := State.S2
         |      else state.din := State.S0
         |    case State.S2 =>
         |      y.din := 0
         |      if (x) state.din := State.S2
         |      else state.din := State.S0
         |  end match
         |end Foo""".stripMargin
    )
  }
  test("process with no steps") {
    class Foo extends RTDesign:
      val x = Bit <> IN
      val y = Bit <> OUT.REG init 0
      process:
        y.din := 0
    end Foo
    val top = (new Foo).dropRTProcess
    assertCodeString(
      top,
      """|class Foo extends RTDesign:
         |  val x = Bit <> IN
         |  val y = Bit <> OUT.REG init 0
         |  y.din := 0
         |end Foo""".stripMargin
    )
  }
  test("fall-through steps") {
    class Foo extends RTDesign:
      val x = Bit <> IN
      val y = Bit <> OUT.REG init 0
      process:
        def S0: Step =
          y.din := 0
          NextStep
        def S1: Step =
          def fallThrough = x
          def onEntry =
            y.din := 1
          NextStep
        def S2: Step =
          def fallThrough = !x
          def onEntry =
            y.din := !y
          NextStep
        def S3: Step =
          def fallThrough = x ^ x.reg(1, init = 0)
          def onEntry =
            y.din := y ^ y.reg
          NextStep
        def S4: Step =
          y.din := 0
          if (x) S2 else S0
    end Foo
    val top = (new Foo).dropRTProcess
    assertCodeString(
      top,
      """|class Foo extends RTDesign:
         |  enum State(val value: UInt[3] <> CONST) extends Encoded.Manual(3):
         |    case S0 extends State(d"3'0")
         |    case S1 extends State(d"3'1")
         |    case S2 extends State(d"3'2")
         |    case S3 extends State(d"3'3")
         |    case S4 extends State(d"3'4")
         |
         |  val x = Bit <> IN
         |  val y = Bit <> OUT.REG init 0
         |  val state = State <> VAR.REG init State.S0
         |  state match
         |    case State.S0 =>
         |      y.din := 0
         |      y.din := 1
         |      state.din := State.S1
         |      if (x)
         |        y.din := !y
         |        state.din := State.S2
         |        if (!x)
         |          y.din := y ^ y.reg
         |          state.din := State.S3
         |          if (x ^ x.reg(1, init = 0)) state.din := State.S4
         |        end if
         |      end if
         |    case State.S1 =>
         |      y.din := !y
         |      state.din := State.S2
         |      if (!x)
         |        y.din := y ^ y.reg
         |        state.din := State.S3
         |        if (x ^ x.reg(1, init = 0)) state.din := State.S4
         |      end if
         |    case State.S2 =>
         |      y.din := y ^ y.reg
         |      state.din := State.S3
         |      if (x ^ x.reg(1, init = 0)) state.din := State.S4
         |    case State.S3 => state.din := State.S4
         |    case State.S4 =>
         |      y.din := 0
         |      if (x)
         |        y.din := !y
         |        state.din := State.S2
         |        if (!x)
         |          y.din := y ^ y.reg
         |          state.din := State.S3
         |          if (x ^ x.reg(1, init = 0)) state.din := State.S4
         |        end if
         |      else state.din := State.S0
         |      end if
         |  end match
         |end Foo""".stripMargin
    )
  }
  test("circular fall-through steps") {
    class Foo extends RTDesign:
      val x = Bit <> IN
      val y = Bit <> OUT.REG init 0
      process:
        def S0: Step =
          def fallThrough = x
          def onEntry =
            y.din := y
          NextStep
        def S1: Step =
          def fallThrough = !x
          def onEntry =
            y.din := !y
          NextStep
        def S2: Step =
          def fallThrough = x ^ x.reg(1, init = 0)
          def onEntry =
            y.din := y ^ y.reg
          NextStep
    end Foo
    val top = (new Foo).dropRTProcess
    assertCodeString(
      top,
      """|class Foo extends RTDesign:
         |  enum State(val value: UInt[2] <> CONST) extends Encoded.Manual(2):
         |    case S0 extends State(d"2'0")
         |    case S1 extends State(d"2'1")
         |    case S2 extends State(d"2'2")
         |
         |  val x = Bit <> IN
         |  val y = Bit <> OUT.REG init 0
         |  val state = State <> VAR.REG init State.S0
         |  state match
         |    case State.S0 =>
         |      y.din := !y
         |      state.din := State.S1
         |      if (!x)
         |        y.din := y ^ y.reg
         |        state.din := State.S2
         |        if (x ^ x.reg(1, init = 0))
         |          y.din := y
         |          state.din := State.S0
         |        end if
         |      end if
         |    case State.S1 =>
         |      y.din := y ^ y.reg
         |      state.din := State.S2
         |      if (x ^ x.reg(1, init = 0))
         |        y.din := y
         |        state.din := State.S0
         |        if (x)
         |          y.din := !y
         |          state.din := State.S1
         |        end if
         |      end if
         |    case State.S2 =>
         |      y.din := y
         |      state.din := State.S0
         |      if (x)
         |        y.din := !y
         |        state.din := State.S1
         |        if (!x)
         |          y.din := y ^ y.reg
         |          state.din := State.S2
         |        end if
         |      end if
         |  end match
         |end Foo""".stripMargin
    )
  }
end DropRTProcessSpec
