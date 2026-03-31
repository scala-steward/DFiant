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
Verilog system functions like `$clog2` have no DFHDL equivalent because DFHDL designs are Scala programs. Use standard Scala expressions at elaboration time instead.

<div class="grid" markdown>

```sv linenums="0" title="Verilog"
parameter RATE = 5208;
localparam WIDTH = $clog2(RATE);
reg [WIDTH-1:0] counter;
```

```scala linenums="0" title="DFHDL"
val RATE: Int <> CONST = 5208
val WIDTH = scala.math.ceil(
  scala.math.log(RATE.toDouble) /
  scala.math.log(2)
).toInt
val counter = UInt(WIDTH) <> VAR
```

</div>

Any Scala expression can be used to compute widths, initial values, and other elaboration-time parameters. This replaces `$clog2`, `$bits`, `$size`, and similar Verilog system functions.
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

