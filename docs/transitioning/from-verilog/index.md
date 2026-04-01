# Transitioning from Verilog to DFHDL

## Using ChatGPT

Help me ChatGPT, you're my only hope

## Summary

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

/// admonition | Elaboration-Time Computation (System Functions)
    type: verilog
Instead of computing widths manually with `$clog2`, use `UInt.until` / `UInt.to` (or `Bits.until` / `Bits.to`) which set the width automatically based on the value range:

- `.until(sup)` — width = `clog2(sup)` (value can be 0 to sup-1)
- `.to(max)` — width = `clog2(max+1)` (value can be 0 to max)

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
parameter RATE = 5208;
localparam WIDTH = $clog2(RATE);
reg [WIDTH-1:0] counter;
```

```scala linenums="0" title="DFHDL"
val RATE: Int <> CONST = 5208
val counter = UInt.until(RATE) <> VAR
```

</div>

See [UInt constructors](../../user-guide/type-system/index.md#unsigned-integer-uint) and [Bits constructors](../../user-guide/type-system/index.md#bit-vector-bits) for details.
///

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
parameter IDLE  = 2'b00,
          START = 2'b01,
          DATA  = 2'b10,
          STOP  = 2'b11;
reg [1:0] state = IDLE;

always @(posedge clk)
  case (state)
    IDLE:  if (go) state <= START;
    START: state <= DATA;
    DATA:  state <= STOP;
    STOP:  state <= IDLE;
  endcase
```

```scala linenums="0" title="DFHDL"
enum State extends Encoded:
  case Idle, Start, Data, Stop

val state = State <> VAR init State.Idle

process(clk):
  if (clk.rising)
    state match
      case State.Idle =>
        if (go) state :== State.Start
      case State.Start =>
        state :== State.Data
      case State.Data =>
        state :== State.Stop
      case State.Stop =>
        state :== State.Idle
```

</div>

If the Verilog state values are non-sequential, use `Encoded.Manual` (see [Manual Encoding](../../user-guide/type-system/index.md#DFEnum)). Avoid modelling FSM states as `Bits` constants -- `match` does not support matching on `Bits <> CONST` names. Use `enum extends Encoded` instead, or fall back to `if`/`else if` chains.
///

/// admonition | Multi-File Projects
    type: verilog
In a scala-cli project with multiple `.scala` files, shared `given` declarations (such as compiler options) must appear in exactly one file. Place them in your `project.scala` file to avoid duplicate definition errors.

```scala title="project.scala"
//> using scala 3.8.1
//> using dep io.github.dfianthdl::dfhdl::0.17.0
//> using plugin io.github.dfianthdl:::dfhdl-plugin:0.17.0

import dfhdl.*
given options.CompilerOptions.Backend = backends.verilog
given options.CompilerOptions.PrintBackendCode = true
```

Individual design files should `import dfhdl.*` but not redeclare the shared `given` options.
///

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

There is no `>>>` operator in DFHDL. The type of the LHS determines the shift semantics: `>>` on `UInt`/`Bits` zero-fills, `>>` on `SInt` sign-extends.
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

Conversion summary: `uint_val.bits.sint` for UInt-to-SInt, `sint_val.bits.uint` for SInt-to-UInt. See [Type Conversion](../../user-guide/type-system/index.md#type-conversion) for details.
///

/// admonition | Bit/Boolean Operators: `|`/`&` and `||`/`&&`
    type: verilog
In DFHDL, `||` and `&&` are equivalent to `|` and `&`, respectively, when applied on `Bit` or `Boolean` types. In the generated Verilog, the operator depends on the LHS type: `Bit` produces `|`/`&`, `Boolean` produces `||`/`&&`.

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
input  a, b, c;
output o1, o2, o3;
// same result for 1-bit
assign o1 = a | b | c;
assign o2 = a | b | c;
assign o3 = a || b || c;
```

```scala linenums="0" title="DFHDL"
val a, b, c = Bit <> IN
val o1, o2, o3 = Bit <> OUT

// Both are equivalent for Bit LHS:
o1 <> a | b | c
o2 <> a || b || c
// a.bool makes the LHS Boolean, so RHS
// auto-converts to Boolean and the result
// auto-converts back to Bit.
// Produces || in Verilog.
o3 <> a.bool || b || c
```

</div>
///

/// admonition | Scalar Reserved Keywords as Port Names
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
///

/// admonition | Bits Initialization
    type: verilog
`Bits` values cannot be initialized with plain integers. Use `all(0)` or a sized literal:

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

`UInt` and `SInt` accept plain integer `0` for init; `Bits` does not.
///

/// admonition | Inline Conditional Expressions
    type: verilog
Verilog's ternary operator `cond ? a : b` maps to Scala's `if`/`else`, but in process blocks the inline form requires parentheses:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
assign out = sel ? a : b;

always @(*)
  out = sel ? a : b;
```

```scala linenums="0" title="DFHDL"
// Continuous: use <> with inline if
out <> (if (sel) a else b)

// In process blocks: wrap in parentheses
process(all):
  out := (if (sel) a else b)

// Or use statement form:
process(all):
  if (sel) out := a
  else out := b
```

</div>

Without parentheses, `out := if (sel) a else b` causes a parse error. Either wrap the `if` in parentheses or use the statement form.
///

/// admonition | Unsigned Literal Minus Signed Expression
    type: verilog
In Verilog, `2 - signed_expr` works because integer literals are implicitly 32-bit. In DFHDL, a plain `2` is unsigned and cannot be subtracted from by a wider signed value. Restructure as negation plus addition:

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
// Works: 2 is 32-bit integer
err <= 2 - (2 * r0);
```

```scala linenums="0" title="DFHDL"
// Restructure: negate then add
err :== (-(r0_wide + r0_wide) + sd"2").truncate

// Or make the signed literal wide enough:
val two = sd"${CORDW+2}'2"
err :== (two - (r0_wide + r0_wide)).truncate
```

</div>
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

/// admonition | `$clog2` Mapping: `.until` vs `.to`
    type: verilog
When translating Verilog `$clog2` expressions, choose the right constructor:

| Verilog | DFHDL | Bits |
|---------|-------|------|
| `$clog2(N)` | `UInt.until(N)` | `clog2(N)` bits, valid for N >= 2 |
| `$clog2(N+1)` | `UInt.to(N)` | `clog2(N+1)` bits, valid for N >= 1 |

`UInt.until(1)` is **invalid** (would produce 0-bit width). For counters that count 0 to N inclusive (common with `$clog2(N+1)`), use `UInt.to(N)`:

```scala linenums="0" title="DFHDL"
// Verilog: reg [$clog2(SCALE+1)-1:0] cnt = 0;
val cnt = UInt.to(SCALE) <> VAR init 0
```
///

/// admonition | Enum FSM and `unique case`
    type: verilog
When you use `enum extends Encoded` with `match` in DFHDL, the generated SystemVerilog uses `unique case`. This has implications for formal verification:

- **Exhaustive enums** (all bit patterns used, e.g., 4 states in 2 bits): `unique case` is safe because every possible value has a branch.
- **Sparse enums** (not all bit patterns used, e.g., 3 states in 2 bits): `unique case` has no `default` for the unused bit patterns. Formal tools may find counterexamples for unreachable states.

If the original Verilog FSM has a `default` branch that handles invalid/unreachable states, use `if`/`else if` chains with a final `else` instead of `match` on an enum:

<div class="grid" markdown>

```sv linenums="0" title="Verilog (3 states, has default)"
case (state)
  IDLE:  ...
  DATA:  ...
  STOP:  ...
  default: state <= IDLE;
endcase
```

```scala linenums="0" title="DFHDL (if/else for default coverage)"
// Use Bits constants + if/else if/else
val IDLE = b"2'00"
val DATA = b"2'01"
val STOP = b"2'10"
val state = Bits(2) <> VAR init IDLE

if (state == IDLE) ...
else if (state == DATA) ...
else if (state == STOP) ...
else state :== IDLE  // covers 2'b11
```

</div>

Use `enum` + `match` when the enum is exhaustive or when `default` coverage is not needed.
///

