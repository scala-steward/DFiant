# Compilation


---

## Elaboration

### Wildcard Arithmetic Value Checking {#wildcard-check}

When [commutative arithmetic operations][arithmetic-ops] (`+`, `*`, `max`, `min`) involve integer wildcards (Scala `Int` or DFHDL `Int` parameters), the wildcard adapts to the counter-part's sign and width. The value is then checked to ensure it fits. This check occurs at three levels, depending on when the value becomes known:

1. **Scala compile-time** -- Literal Scala integers (e.g., `u8 + 1000`) have known values at compile time. The Scala compiler reports an error immediately if the value exceeds the counter-part's range or has incompatible sign.

2. **DFHDL elaboration-time** -- Non-literal Scala integers (e.g., `val x: Int = computeValue(); u8 + x`) and DFHDL `Int` constants whose values are resolved during elaboration. A DFHDL elaboration error (Scala runtime error) is generated if the value does not fit.

3. **Synthesis/simulation-time** -- DFHDL `Int` parameters that are set externally or computed in complex generation loops may not be known until synthesis or simulation. Assertions must be added to verify these values at the target platform level. This is a planned future feature (TODO).
