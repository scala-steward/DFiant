# Naming

## Reserved Names

When translating from Verilog, signal and module names may conflict with names already in scope from Scala or DFHDL. There are two categories of conflicts:

### Scala Reserved Keywords

Scala keywords cannot be used directly as identifiers. Use backtick escaping:

`val`, `var`, `def`, `type`, `class`, `object`, `trait`, `enum`, `match`, `case`, `if`, `else`, `for`, `while`, `do`, `return`, `throw`, `try`, `catch`, `finally`, `yield`, `import`, `export`, `new`, `this`, `super`, `true`, `false`, `null`, `then`, `end`, `given`, `using`, `extension`, `with`, `abstract`, `final`, `override`, `sealed`, `lazy`, `private`, `protected`

```scala
// Verilog signal named "val"
val `val` = SInt(16) <> OUT
`val` <> 42
```

### DFHDL Built-in Names

`import dfhdl.*` brings DFHDL built-in functions and types into scope. If a user-defined class has the same name as a built-in, the built-in shadows the class. Known built-ins that commonly conflict with Verilog module names:

`abs`, `clog2`, `max`, `min`, `all`, `Bit`, `Bits`, `UInt`, `SInt`

```scala
// Module named "abs" conflicts with dfhdl.abs
class abs(val DATA_WIDTH: Int <> CONST = 8) extends EDDesign:
  // ...

// In the parent design, `abs(...)` resolves to the built-in function.
// Fix: create a type alias before instantiation
type AbsModule = abs
val u_abs = AbsModule(DATA_WIDTH = 16)
```

## Resolution Patterns

### Backtick Escaping

For Scala keywords used as signal names:

```scala
val `type` = UInt(8) <> IN
val `match` = Bit <> OUT
```

### `@targetName` Annotation

When a Scala-side name must differ from the generated HDL name, use `@targetName` to set the hardware name explicitly. This is useful when:

- A port name conflicts with a sub-module class name in the same design
- You want to rename a Scala identifier but preserve the original Verilog port name

```scala
import scala.annotation.targetName

// Port "kernel" conflicts with class "kernel" in scope
@targetName("kernel")
val kernel_out = Bits(WIDTH) <> OUT
// Generated HDL port is still named "kernel"

// The class "kernel" remains available for instantiation
val u_kernel = kernel()
```

### Type Alias for Class Name Conflicts

When a class name conflicts with a DFHDL built-in function:

```scala
type AbsModule = abs   // alias resolves the class, not the function
val u_abs = AbsModule(DATA_WIDTH = 8)
```
