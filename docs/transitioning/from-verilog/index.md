# Transitioning from Verilog to DFHDL

This guide helps Verilog/SystemVerilog users translate common patterns into DFHDL. For full type system details, see the [Type System reference][type-system].

## Design Structure

/// admonition | Module Definition
    type: verilog
<div class="grid" markdown>

```sv linenums="0" title="Verilog"
module _module_name_ #(
  //param declarations
) (
  //port declarations
);
  //internal declarations
endmodule
```

```scala linenums="0" title="DFHDL"
class _design_name_(
  //param declarations
) extends EDDesign:
  //port & internal declarations


end _design_name_ //optional
```

```sv linenums="0" title="Verilog"
module AndGate (
  input a, b;
  output o
);
  assign o = a & b
endmodule
```

```scala linenums="0" title="DFHDL"
class AndGate extends EDDesign:
  val a, b = Bit <> IN
  val o    = Bit <> OUT

  o <> a && b
end AndGate
```

</div>
///

/// admonition | Parameter Declarations
    type: verilog
<div class="grid" markdown>

```sv linenums="0" title="Verilog"
parameter [7:0] p = 8’b1011;
```

```scala linenums="0" title="DFHDL"
val p: Bits[8] <> CONST = b"8'1011"
```

```sv linenums="0" title="Verilog"
module Concat #(
  parameter  int len1;
  parameter  int len2;
  localparam int outlen = len1 + len2
) (
  input  [len1-1:0]   i1;
  input  [len2-1:0]   i2;
  output [outlen-1:0] o
);
  assign o = {i1, i2};
endmodule
```

```scala linenums="0" title="DFHDL"
class Concat(
    val len1: Int <> CONST
    val len2: Int <> CONST
) extends EDDesign:
  val outlen = len1 + len2
  val i1 = Bits(len1)   <> IN
  val i2 = Bits(len2)   <> IN
  val o  = Bits(outlen) <> OUT
  
  o <> (i1, i2)
end Concat
```

</div>
///

/// admonition | logic/reg/wire
    type: verilog
<div class="grid" markdown>

```sv linenums="0" title="Verilog"
logic [7:0] v = 8’b1011;
wire  [7:0] v = 8’b1011;
reg   [7:0] v = 8’b1011;
```

```scala linenums="0" title="DFHDL"
val v = Bits(8) <> VAR init b"8’1011"
```

</div>
///

## Types and Literals

/// admonition | Numeric Literals
    type: verilog
DFHDL uses string interpolators for sized literals. Each type has its own interpolator -- do not mix Verilog base prefixes (`’b`, `’d`, `’h`) inside them.

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
8’b1011_0000   // binary
8’hB0          // hex
8’d176         // decimal
5’d27          // 5-bit decimal
```

```scala linenums="0" title="DFHDL"
b"8’1011_0000"  // binary (b"...")
h"8’B0"         // hex (h"...")
d"8’176"        // unsigned decimal (d"...")
d"5’27"         // 5-bit unsigned decimal
```

</div>

`b"..."` accepts only binary digits (`0`, `1`, `?`). Writing `b"5’d27"` is an error -- use `d"5’27"` for decimal values.
///

/// admonition | `$clog2` and Width Computation
    type: verilog
Instead of computing widths manually with `$clog2`, use `.until` or `.to` constructors which set the width automatically:

| Verilog | DFHDL | Width |
|---------|-------|-------|
| `$clog2(N)` | `UInt.until(N)` / `Bits.until(N)` | `clog2(N)` bits, valid for N >= 2 |
| `$clog2(N+1)` | `UInt.to(N)` / `Bits.to(N)` | `clog2(N+1)` bits, valid for N >= 1 |

`UInt.until(1)` is **invalid** (would produce 0-bit width). For counters that count 0 to N inclusive (common with `$clog2(N+1)`), use `UInt.to(N)`.

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
parameter RATE = 5208;
localparam WIDTH = $clog2(RATE);
reg [WIDTH-1:0] counter;

// Counter 0..SCALE inclusive
reg [$clog2(SCALE+1)-1:0] cnt = 0;
```

```scala linenums="0" title="DFHDL"
val RATE: Int <> CONST = 5208
val counter = UInt.until(RATE) <> VAR


// Counter 0..SCALE inclusive
val cnt = UInt.to(SCALE) <> VAR init 0
```

</div>

See [UInt/SInt constructors][DFDecimal] and [Bits constructors][DFBits] for details. See also the [`clog2` anti-pattern warning][int-param-ops].
///

## Processes and Sequential Logic

/// admonition | Process Blocks (always)
    type: verilog
Verilog `always` blocks map to DFHDL ED domain `process(...)` blocks.

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
// Combinational
always @(*) begin
  y = a + b;
end

// Sequential (clocked)
always @(posedge clk) begin
  counter <= counter + 1;
end

// Sequential with async reset
always @(posedge clk or posedge rst)
  if (rst)
    q <= 0;
  else
    q <= d;
```

```scala linenums="0" title="DFHDL"
// Combinational
process(all):
  y := a + b


// Sequential (clocked)
process(clk):
  if (clk.rising)
    counter :== counter + 1

// Sequential with async reset
process(clk, rst):
  if (rst)
    q :== 0
  else if (clk.rising)
    q :== d
```

</div>

- `always @(*)` becomes `process(all):`
- `always @(posedge clk)` becomes `process(clk):` with `if (clk.rising)` inside
- Verilog blocking `=` becomes DFHDL `:=` (use in combinational processes)
- Verilog non-blocking `<=` becomes DFHDL `:==` (use in clocked processes)
///

/// admonition | Variable Initialization (init)
    type: verilog
In ED domain, `init` on a `VAR` generates a Verilog `reg` with an initial value. This maps to both `initial begin` blocks and `reg ... = value` declarations.

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
reg [7:0] counter = 8’d0;
// or equivalently:
// initial counter = 8’d0;
```

```scala linenums="0" title="DFHDL"
val counter = UInt(8) <> VAR init 0
```

</div>

An `output reg` with an initial value maps directly to `OUT init`:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
module Foo(
  input  clk,
  input  din,
  output reg dout
);
  initial dout = 1'b1;
  always @(posedge clk)
    dout <= din;
endmodule
```

```scala linenums="0" title="DFHDL"
@top class Foo extends EDDesign:
  val clk  = Bit <> IN
  val din  = Bit <> IN
  val dout = Bit <> OUT init 1
  process(clk):
    if (clk.rising)
      dout :== din
```

</div>
///

/// admonition | FSM State Encoding
    type: verilog
Verilog FSMs typically use `parameter` constants and `case`/`if` chains. In DFHDL, the idiomatic translation uses an `enum extends Encoded` and `match`:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
parameter READY = 2'b00,
          AIM   = 2'b01,
          FIRE  = 2'b10;
reg [1:0] state = READY;

always @(posedge clk)
  case (state)
    READY:   if (go) state <= AIM;
    AIM:     state <= FIRE;
    FIRE:    state <= READY;
    default: state <= READY;
  endcase
```

```scala linenums="0" title="DFHDL"
enum State extends Encoded:
  case Ready, Aim, Fire
import State.*
val state = State <> VAR init Ready

process(clk):
  if (clk.rising)
    state match
      case Ready => if (go) state :== Aim
      case Aim   => state :== Fire
      case Fire  => state :== Ready
      case _     => state :== Ready
```

</div>

If the encoded Verilog state values have no standard pattern (incremental, gray, one-hot), use `Encoded.Manual` (see [Enumeration][DFEnum]). Avoid modeling FSM states as `Bits` or `UInt` constants, it's an anti-pattern. When compiling to SystemVerilog (SV), the SV enums are being utilized as well.
///

## Operations

/// admonition | Shift Operators
    type: verilog
Verilog has separate `>>` (logical) and `>>>` (arithmetic) right shift operators. DFHDL uses only `>>`, but the behavior depends on the operand type:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
// Logical right shift (unsigned)
out = data >> 2;

// Arithmetic right shift (signed)
out = $signed(data) >>> 2;
```

```scala linenums="0" title="DFHDL"
// Logical right shift (UInt or Bits)
out := data >> 2

// Arithmetic right shift (SInt)
out := data_signed >> 2
```

</div>

There is no `>>>` operator in DFHDL. The type of the LHS determines the shift semantics: `>>` on `UInt`/`Bits` zero-fills, `>>` on `SInt` sign-extends. See [Shift Operations][shift-ops] for details.
///

/// admonition | UInt/SInt Conversion
    type: verilog
Verilog implicitly converts between signed and unsigned in mixed expressions. DFHDL requires explicit conversion through `Bits`:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
reg [2:0] counter;  // unsigned
reg signed [7:0] offset;

// Verilog auto-converts
offset = 180 - 5 * counter;
```

```scala linenums="0" title="DFHDL"
val counter = UInt(3) <> VAR
val offset  = SInt(8) <> VAR

// Must convert UInt -> SInt explicitly
// UInt -> .bits -> .sint -> .resize
val cnt_s = counter.bits.sint.resize(8)
offset := sd"8'180" - cnt_s * 5
```

</div>

Conversion options:

- **UInt → SInt (width+1):** `uint_val.signed` — adds a sign bit, widening by 1. Preferred when you can accept the extra bit.
- **UInt → SInt (same width):** `uint_val.bits.sint` — reinterprets the bit pattern without widening.
- **SInt → UInt (same width):** `sint_val.bits.uint` — reinterprets the bit pattern.

See [Conversions and Casts][type-conversion] for the full conversion reference.
///

/// admonition | Bit/Boolean Operators: `|`/`&` and `||`/`&&`
    type: verilog
In DFHDL, `||`/`&&` and `|`/`&` are interchangeable on `Bit` and `Boolean` types. The generated Verilog operator depends on the LHS type: `Bit` produces bitwise `|`/`&`, `Boolean` produces logical `||`/`&&`.
<div class="grid" markdown>

```sv linenums="0" title="Verilog"
input  a, b, c;
output o1, o2, o3;
assign o1 = a | b | c;
assign o2 = a | b | c;
assign o3 = a || b || c;
```

```scala linenums="0" title="DFHDL"
val a, b, c = Bit <> IN
val o1, o2, o3 = Bit <> OUT
o1 <> a | b | c
o2 <> a || b || c
o3 <> a.bool || b || c
```

</div>

See [Logical Operations][logical-ops] for the full reference and Verilog/VHDL mapping tables.
///

## Common Pitfalls

/// admonition | Scala Reserved Keywords as DFHDL Port or Variable Names
    type: verilog
Some Verilog port names (`val`, `type`, `class`, `match`, `case`, `object`, etc.) are reserved in Scala. Use backtick escaping:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
module foo(
  output reg signed [15:0] val
);
  assign val = 16'sd42;
```

```scala linenums="0" title="DFHDL"
class foo extends EDDesign:
  val `val` = SInt(16) <> OUT
  `val` <> 42
```

</div>

Alternatively, use a non-keyword name with the Scala `@targetName` annotation to set the actual HDL name:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
module foo(
  output logic signed [15:0] class
);
  assign class = 16'sd42;
endmodule
```

```scala linenums="0" title="DFHDL"
import scala.annotation.targetName
class foo extends EDDesign:
  @targetName("class") 
  val class_ = SInt(16) <> OUT
  class_ <> 42
```

</div>


///

/// admonition | `Bits` Initialization or Assignment
    type: verilog
`Bits` values cannot be initialized or assigned with plain integers. Use `all(0)` or a sized literal:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
reg [7:0] flags = 8'd0;
reg [7:0] mask  = 8'hFF;
```

```scala linenums="0" title="DFHDL"
val flags = Bits(8) <> VAR init all(0)
val mask  = Bits(8) <> VAR init h"8'FF"
// NOT: Bits(8) <> VAR init 0  // error
```

</div>

Note: `UInt` and `SInt` can be initialized or assigned with plain integers.
///

/// admonition | Inline Conditional Expressions
    type: verilog
Verilog's ternary operator `cond ? a : b` has three DFHDL equivalents:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
assign out = sel ? a : b;

always @(*)
  out = sel ? a : b;
```

```scala linenums="0" title="DFHDL"
// 1. Using .sel (closest to ternary)
out <> sel.sel(a, b)

// 2. Inline if/else (wrap in parentheses)
out <> (if (sel) a else b)

// 3. Statement form
if (sel) out := a
else out := b
```

</div>

The `.sel` method compiles directly to Verilog's ternary operator. For complex nested conditions, prefer `if`/`else` or `match` over chaining `.sel` calls. See [Selection (.sel)][sel-ops] for details.
///

/// admonition | Unsigned Literal Minus Signed Expression
    type: verilog
In Verilog, `2 - signed_expr` works because integer literals are implicitly 32-bit signed. In DFHDL, a plain `2` is unsigned, so `2 - signed_val` is a compile error (unsigned LHS cannot accept signed RHS).

The simplest fix is to use a signed literal with `sd"..."`:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
// Works: 2 is 32-bit integer
err <= 2 - (2 * r0);
```

```scala linenums="0" title="DFHDL"
// Use a signed literal wide enough for the result:
val two = sd"${CORDW+2}'2"
err :== (two - (r0_wide + r0_wide)).truncate

// Or restructure as negation + addition:
err :== (-(r0_wide + r0_wide) + sd"2").truncate
```

</div>

See [Arithmetic type constraints][arithmetic-ops] for the sign and width rules.
///

/// admonition | Parametric Bit-Vector Constants
    type: verilog
DFHDL does not support `Bits` CONST parameters whose width depends on another parameter. Use `Int <> CONST` and extract bits internally:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
parameter LEN=8;
parameter TAPS=8'b10111000;
// TAPS width depends on LEN
```

```scala linenums="0" title="DFHDL"
val LEN:  Int <> CONST = 8
val TAPS: Int <> CONST = 0xB8

// Extract bits internally
val taps_b = Bits(LEN) <> VAR
taps_b <> TAPS.bits(LEN-1, 0)
```

</div>

Note: `.bits(hi, lo)` is an extension method on `Int <> CONST` DFHDL values. It does **not** work on plain Scala `Int`. If you have a compile-time constant, pass it as an `Int <> CONST` parameter, or use a hex/binary literal directly: `h"21'140000"`.
///

