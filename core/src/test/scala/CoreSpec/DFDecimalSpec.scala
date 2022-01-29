package CoreSpec
import DFiant.*
import munit.*
import internals.Inlined

import scala.annotation.implicitNotFound

class DFDecimalSpec extends DFSpec:
  test("Type Construction") {
    val zero = 0
    assertDSLError(
      "Unsigned value width must be positive, but found: 0"
    )(
      """DFUInt(0)"""
    ) {
      DFUInt(zero)
    }
    val one = 1
    assertDSLError(
      "Signed value width must be larger than 1, but found: 1"
    )(
      """DFSInt(1)"""
    ) {
      DFSInt(one)
    }
    assertCodeString {
      """val x = DFUInt(8) <> VAR init d"8'0"
        |val y = DFSInt(8) <> VAR init sd"8'-1"
        |val z = DFSInt(8) <> VAR init sd"8'0"
        |""".stripMargin
    } {
      val x = DFUInt(8) <> VAR init 0
      val y = DFSInt(8) <> VAR init -1
      val z = DFSInt(8) <> VAR init 0
    }
  }

  val u7 = DFUInt(7)
  val s5 = DFSInt(5)
  test("Inlined width") {
    u7.width.verifyInlined(7)
    s5.width.verifyInlined(5)
  }
  test("Token Construction") {
    val t1 = (DFUInt(8) token 100).verifyTokenOf[DFUInt[8]]
    val t1b = (DFSInt(8) token -1).verifyTokenOf[DFSInt[8]]
    val t2 = d"255".verifyTokenOf[DFUInt[8]]
    val t3 = d"256".verifyTokenOf[DFUInt[9]]
    val t4 = d"0".verifyTokenOf[DFUInt[1]]
    val t5 = d"10'0".verifyTokenOf[DFUInt[10]]
    val t6 = d"-1".verifyTokenOf[DFSInt[2]]
    val t7 = sd"-1".verifyTokenOf[DFSInt[2]]
    val t8 = sd"0".verifyTokenOf[DFSInt[2]]
    val t8_a = d"-128".verifyTokenOf[DFSInt[8]]
    val t8_b = d"-65".verifyTokenOf[DFSInt[8]]
    val t8_c = d"-64".verifyTokenOf[DFSInt[7]]
    val t9 = DFUInt(8).token(1)
    val t10 = DFUInt(8).token(d"1")
    val t11 = DFUInt(8).token(?)
    val t12 = DFSInt(8).token(1)
    val t13 = DFSInt(8).token(-1)
    val t14 = DFSInt(8).token(?)
    val t15 = DFSInt(8).token(127)
    val t16 = DFSInt(8).token(d"127")
    val t17 = DFSInt(8).token(sd"127")
    assert(t15.asIR equals t16.asIR)
    assert(t16.asIR equals t17.asIR)

    assertCompileError("Invalid decimal pattern found: 1x")("""d"1x"""")
    assertCompileError(
      "Explicit given width (4) is smaller than the actual width (8)"
    )("""d"4'255"""")
    assertCompileError(
      "Explicit given width (7) is smaller than the actual width (8)"
    )("""d"7'-128"""")
    val negOne = -1
    assertDSLError(
      "Cannot apply a signed value to an unsigned variable."
    )(
      """DFUInt(8).token(-1)"""
    ) {
      DFUInt(8).token(negOne)
    }
    assertCompileError(
      "Cannot apply a signed value to an unsigned variable."
    )("""DFUInt(8).token(sd"1")""")
    assertDSLError(
      "The applied RHS value width (9) is larger than the LHS variable width (8)."
    )(
      """DFSInt(8).token(128)"""
    ) {
      val value = 128
      DFSInt(8).token(value)
    }
    assertDSLError(
      "The applied RHS value width (9) is larger than the LHS variable width (8)."
    )(
      """DFSInt(8).token(d"128")"""
    ) {
      val value = 8
      DFSInt(value).token(d"128")
    }
  }
  test("Token Resize") {
    assertEquals(d"255".resize(4), d"15")
    assertEquals(d"15".resize(8), d"8'15")
    assertEquals(d"-15".resize(8), d"8'-15")
    assertEquals(d"8'-1".resize(4), d"4'-1")
    assertEquals(d"8'-1".resize(8), d"8'-1")
    assertEquals(b"1001".uint.resize(3), d"3'1")
    assertEquals(b"1001".sint.resize(3), d"3'-3")
    val zero = 0
    assertDSLError(
      "Unsigned value width must be positive, but found: 0"
    )(
      """d"15".resize(0)"""
    ) {
      d"15".resize(zero)
    }
    val one = 1
    assertDSLError(
      "Signed value width must be larger than 1, but found: 1"
    )(
      """b"1001".sint.resize(1)"""
    ) {
      b"1001".sint.resize(one)
    }
  }
  test("Token Conversion") {
    assertEquals(d"255".bits, h"FF")
    assertEquals(h"FF".uint, d"255")
    assertEquals(d"8'-1".bits, h"FF")
    assertEquals(h"FF".sint, d"8'-1")
    assertEquals(DFUInt(8).token(?).bits, h"??")
    assertEquals(DFSInt(3).token(?).bits, b"???")
    assertEquals(b"111?".uint, DFUInt(4).token(?))
    assertEquals(b"111?".sint, DFSInt(4).token(?))
    assertEquals(d"8".signed, DFSInt(5).token(8))
  }
  test("DFVal Conversion") {
    assertCodeString {
      """|val t0 = DFBits(6) const h"6'00"
         |val t2 = DFUInt(8) <> VAR
         |t2 := t0.uint.resize(8)
         |""".stripMargin
    } {
      val t0 = DFBits(6) const all(0)
      val t1: DFUInt[8] <> VAL = t0
      val t2 = DFUInt(8) <> VAR
      t2 := t1
    }
  }
  test("Assignment") {
    assertCodeString {
      """|val u8 = DFUInt(8) <> VAR init d"8'255"
         |val s8 = DFSInt(8) <> VAR init ?
         |val u6 = DFUInt(6) <> IN
         |val s6 = DFSInt(6) <> IN
         |val b6 = DFBits(6) const h"6'00"
         |u8 := d"8'0"
         |u8 := d"8'255"
         |u8 := d"8'0"
         |u8 := ?
         |u8 := d"8'7"
         |u8 := b6.uint.resize(8)
         |u8 := u6.resize(8)
         |s8 := sd"8'0"
         |s8 := sd"8'127"
         |s8 := sd"8'0"
         |s8 := ?
         |s8 := sd"8'-1"
         |s8 := sd"8'-127"
         |s8 := u6.signed.resize(8)
         |s8 := s6.resize(8)
         |""".stripMargin
    } {
      val u8 = DFUInt(8) <> VAR init 255
      val s8 = DFSInt(8) <> VAR init ?
      val u6 = DFUInt(6) <> IN
      val s6 = DFSInt(6) <> IN
      val b6 = DFBits(6) const all(0)
      u8 := 0
      u8 := 255
      u8 := d"0"
      u8 := ?
      u8 := b"111"
      u8 := b6
      u8 := u6
      s8 := 0
      s8 := 127
      s8 := d"0"
      s8 := ?
      s8 := -1
      s8 := -127
      s8 := u6
      s8 := s6
      assertDSLError(
        "Cannot apply a signed value to an unsigned variable."
      )(
        """u8 := -1"""
      ) {
        val value = -1
        u8 := value
      }
      assertCompileError(
        "Cannot apply a signed value to an unsigned variable."
      )(
        """u8 := s8"""
      )
      assertDSLError(
        "The applied RHS value width (9) is larger than the LHS variable width (8)."
      )(
        """u8 := 256"""
      ) {
        val value = 256
        u8 := value
      }
      assertDSLError(
        "The applied RHS value width (9) is larger than the LHS variable width (8)."
      )(
        """s8 := 128"""
      ) {
        val value = 128
        s8 := value
      }
      assertCompileError(
        "The applied RHS value width (9) is larger than the LHS variable width (8)."
      )(
        """s8 := u8"""
      )
    }
  }
  test("Comparison") {
    val u8 = DFUInt(8) <> VAR
    val u7 = DFUInt(7) <> VAR
    val s8 = DFSInt(8) <> VAR
    val b8 = DFBits(8) <> VAR
    assertEquals(d"22" == d"22", DFBool.token(true))
    assertEquals(d"22" != d"22", DFBool.token(false))
    assertEquals(d"22" < 23, DFBool.token(true))
    assertEquals(24 > d"18", DFBool.token(true))
    assertEquals(d"22" <= 21, DFBool.token(false))
    assertEquals(24 >= d"24", DFBool.token(true))
    assertCodeString {
      """|val t1 = u8 == u8
         |val t2 = u8 != d"8'0"
         |val t3 = d"8'0" < u8
         |val t4 = u8 > d"8'12"
         |val t5 = u8 != d"8'255"
         |val t6 = u8 <= b8.uint
         |val t7 = u8.resize(4) >= b8.resize(4).uint
         |""".stripMargin
    } {
      val t1 = u8 == u8
      val t2 = u8 != 0
      val t3 = 0 < u8
      val t4 = u8 > d"8'12"
      val t5 = u8 != h"FF"
      val t6 = u8 <= b8
      val t7 = u8.resize(4) >= b8.resize(4)
    }

    assertDSLError(
      """|Cannot apply this operation between a value of 8 bits width (LHS) to a value of 7 bits width (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """u8 == u7"""
    ) {
      val value = 7
      val u7 = DFUInt(value) <> VAR
      u8 == u7
    }
    assertDSLError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """u8 < -1"""
    ) {
      val value = -1
      u8 < value
    }
    assertDSLError(
      """|Cannot apply this operation between a signed value (LHS) and an unsigned value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """-1 <= u8"""
    ) {
      val value = -1
      value <= u8
    }
    assertDSLError(
      """Cannot compare a dataflow value (width = 8) with a Scala `Int` argument that is wider (width = 10).
        |An explicit conversion must be applied.
        |""".stripMargin
    )(
      """u8 > 1000"""
    ) {
      val value = 1000
      u8 > value
    }
  }
  test("Arithmetic") {
    assertEquals(d"8'22" + d"8'22", d"8'44")
    assertEquals(d"8'22" +^ d"8'22", d"9'44")
    assertEquals(d"5'22" +^ d"8'22", d"9'44")
    assertEquals(d"8'22" +^ d"5'22", d"9'44")
    assertEquals(sd"8'22" + sd"8'22", sd"8'44")
    assertEquals(sd"8'22" + 22, sd"8'44")
    assertEquals(d"8'22" + h"4", d"8'26")
    assertEquals(d"8'255" + d"8'1", d"8'0")
    assertEquals(d"8'255" +^ d"8'1", d"9'256")
    assertEquals(sd"9'255" + sd"8'1", sd"9'-256")
    assertEquals(sd"9'255" +^ sd"8'1", sd"10'256")
    assertEquals(200 + d"8'1", d"8'201")
    assertEquals(200 +^ d"8'1", d"9'201")
    assertEquals(-200 + sd"8'1", sd"9'-199")
    assertCompileError(
      """|Cannot apply this operation between a signed value (LHS) and an unsigned value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """sd"8'22" + d"8'22""""
    )
    assertCompileError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """d"8'22" + sd"8'22""""
    )
    assertCompileError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """h"8'22" + sd"8'22""""
    )
    assertCompileError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """d"8'22" + (-22)"""
    )
    assertCompileError(
      "The applied RHS value width (9) is larger than the LHS variable width (8)."
    )(
      """d"8'22" + d"9'22""""
    )
    assertEquals(d"8'22" + 200, d"8'222")
    assertEquals(sd"8'-1" + 1, sd"8'0")
    assertDSLError(
      "The applied RHS value width (9) is larger than the LHS variable width (8)."
    )(
      """sd"8'22" + 200"""
    ) {
      val value = 200
      sd"8'22" + value
    }
    assertDSLError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """22 + sd"8'22""""
    ) {
      val value = 22
      value + sd"8'22"
    }
    assertEquals(d"8'22" - d"8'22", d"8'0")
    assertEquals(sd"8'22" - sd"8'22", sd"8'0")
    assertEquals(sd"8'22" - 22, sd"8'0")
    assertEquals(d"8'22" - b"1001", d"8'13")
    assertEquals(d"8'22" - d"8'23", d"8'255")
    assertEquals(sd"8'22" - sd"8'23", sd"8'-1")
    assertCompileError(
      """|Cannot apply this operation between a signed value (LHS) and an unsigned value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """sd"8'22" - d"8'22""""
    )
    assertCompileError(
      """|Cannot apply this operation between a signed value (LHS) and an unsigned value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """sd"8'22" - h"8'22""""
    )
    assertCompileError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """d"8'22" - sd"8'22""""
    )
    assertCompileError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """d"8'22" - (-22)"""
    )
    assertCompileError(
      "The applied RHS value width (9) is larger than the LHS variable width (8)."
    )(
      """d"8'22" - d"9'22""""
    )
    assertEquals(d"8'22" - 200, d"8'78")
    assertEquals(sd"9'22" - 200, sd"9'-178")
    assertDSLError(
      "The applied RHS value width (9) is larger than the LHS variable width (8)."
    )(
      """sd"8'22" - 200"""
    ) {
      val value = 200
      sd"8'22" - value
    }
    assertDSLError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """22 - sd"8'22""""
    ) {
      val value = 22
      value - sd"8'22"
    }

    assertEquals(d"8'22" * d"8'2", d"8'44")
    assertEquals(d"8'22" / d"8'2", d"8'11")
    assertEquals(d"8'22" % d"8'2", d"8'0")
    assertEquals(100 * d"7'2", d"7'72")
    assertEquals(100 / d"7'2", d"7'50")
    assertEquals(17 % d"3'2", d"5'1")
    assertEquals(d"8'22" *^ d"8'2", d"16'44")
    assertEquals(100 *^ d"7'2", d"14'200")

    val u8 = DFUInt(8) <> VAR
    val u7 = DFUInt(7) <> VAR
    val s8 = DFSInt(8) <> VAR
    val b8 = DFBits(8) <> VAR
    assertCodeString {
      """|val t1 = u8 + u8
         |val t2 = u8 - d"1'0"
         |val t3 = d"8'200" - u8
         |val t4 = s8 / sd"3'2"
         |val t5 = u8 % d"4'9"
         |val t6 = u8 * d"5'22"
         |val t7 = s8 + sd"6'22"
         |val t8 = s8 +^ sd"2'1"
         |val t9 = u8 -^ d"5'22"
         |val t10 = d"7'100" *^ u8
         |""".stripMargin
    } {
      val t1 = u8 + u8
      t1.verifyValOf[DFUInt[8]]
      val t2 = u8 - 0
      t2.verifyValOf[DFUInt[8]]
      val t3 = 200 - u8
      t3.verifyValOf[DFUInt[8]]
      val t4 = s8 / 2
      t4.verifyValOf[DFSInt[8]]
      val t5 = u8 % 9
      t5.verifyValOf[DFUInt[8]]
      val t6 = u8 * d"22"
      t6.verifyValOf[DFUInt[8]]
      val t7 = s8 + sd"22"
      t7.verifyValOf[DFSInt[8]]
      val t8 = s8 +^ 1
      t8.verifyValOf[DFSInt[9]]
      val t9 = u8 -^ d"22"
      t9.verifyValOf[DFUInt[9]]
      val t10 = 100 *^ u8
      t10.verifyValOf[DFUInt[15]]
    }
    assertCompileError(
      """|Cannot apply this operation between a signed value (LHS) and an unsigned value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """s8 + d"8'22""""
    )
    assertCompileError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """u8 - sd"8'22""""
    )
    assertCompileError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """b8 * s8"""
    )
    assertCompileError(
      """|Cannot apply this operation between an unsigned value (LHS) and a signed value (RHS).
         |An explicit conversion must be applied.
         |""".stripMargin
    )(
      """u8 / (-22)"""
    )
    assertCompileError(
      "The applied RHS value width (9) is larger than the LHS variable width (8)."
    )(
      """u8 % d"9'22""""
    )
  }
end DFDecimalSpec