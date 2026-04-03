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
</div>

Inter-dependent parameters and ports example:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
module Concat#(
    parameter int len1 = 8,
    parameter int len2 = 8,
    parameter logic [7:0] midVec = 8'h55
)(
  input  wire logic [len1 - 1:0]   i1,
  input  wire logic [len2 - 1:0]   i2,
  output      logic [outlen - 1:0] o
);
  localparam int midLen = 8;
  localparam int outlen = len1 + midLen + len2;
  assign o = {i1, midVec, i2};
endmodule
```

```scala linenums="0" title="DFHDL"
class Concat(
    val len1: Int <> CONST = 8,
    val len2: Int <> CONST = 8,
    val midVec: Bits[Int] <> CONST = h"55"
) extends EDDesign:
  val midLen = midVec.width
  val outlen = len1 + midLen + len2
  val i1 = Bits(len1) <> IN
  val i2 = Bits(len2) <> IN
  val o = Bits(outlen) <> OUT

  o <> (i1, midVec, i2)
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
module RightShifter(
  input  wire logic [7:0] data,
  output      logic [7:0] logical,
  output      logic [7:0] arith
);
  assign logical = data >> 1;
  assign arith   = data >>> 1;
endmodule
```

```scala linenums="0" title="DFHDL"
class RightShifter extends EDDesign:
  val data    = Bits(8) <> IN
  val logical = Bits(8) <> OUT
  val arith   = Bits(8) <> OUT

  logical <> data >> 1
  arith   <> (data.sint >> 1).bits
end RightShifter
```

</div>

There is no `>>>` operator in DFHDL. The type of the LHS determines the shift semantics: `>>` on `UInt`/`Bits` zero-fills, `>>` on `SInt` sign-extends. See [Shift Operations][shift-ops] for details.
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

## Parametric Constants

/// admonition | Parametric-Width Bits Constants
    type: verilog
Verilog parameters can be bit-vector constants whose width depends on another parameter. In DFHDL, use `Bits[Int] <> CONST` (unbounded width) for the parameter. The width is inferred from the default literal value, and other local values can derive their widths from it:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
module MyDesign #(
    parameter WIDTH = 8,
    parameter [WIDTH-1:0] MASK = 8'hB8
)(
    output logic [WIDTH-1:0] data
);
  // ...
endmodule
```

```scala linenums="0" title="DFHDL"
class MyDesign(
    val WIDTH: Int <> CONST = 8,
    val MASK:  Bits[Int] <> CONST = h"B8"
) extends EDDesign:
  // MASK.width gives the actual width
  val data = Bits(MASK.width) <> OUT
  // ...
```

</div>

See the [Parameter Declarations](#parameter-declarations) section above for a complete inter-dependent parameters example.
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
assign out = cond ? a : b;

always @(*)
  out = cond ? a : b;
```

```scala linenums="0" title="DFHDL"
// 1. Using .sel (closest to ternary)
out <> cond.sel(a, b)

// 2. Inline if/else (wrap in parentheses)
out <> (if (cond) a else b)

// 3. Statement form
if (cond) out := a
else out := b
```

</div>

The `.sel` method compiles directly to Verilog's ternary operator. For complex nested conditions, prefer `if`/`else` or `match` over chaining `.sel` calls. See [Selection (.sel)][sel-ops] for details.

When using inline `if`/`else` as the RHS of `:=` or `:==`, **parentheses are required**. Without them, Scala 3 parses the `if` as a statement, not an expression:

```scala
// CORRECT: parenthesized inline if with := and <>
out := (if (cond) a else b)
out <> (if (cond) a else b)

// PARSE ERROR: bare inline if on RHS of := or <>
// out := if (cond) a else b  // "end of statement expected"

// CORRECT: statement form (no parentheses needed)
if (cond) out := a
else out := b
```

This applies to all assignment operators (`:=`, `:==`, `<>`). Use `.sel` or the parenthesized form for inline conditionals; use the statement form for multi-assignment branches.
///

