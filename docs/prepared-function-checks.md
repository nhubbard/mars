# Prepared function checks

`PreparedFunctionChecks` evaluates bounded function-call contracts against an
existing `AssemblyImage`. It is the data-only counterpart of the assignment DSL's
`enterFunction`, `runUntil`, register assertions, caller-byte comparisons and
execution traces. It does not evaluate an assignment script or change the ISA.
The native lesson JSON reader does not yet expose this new checker; its versioned
content and UI integration are a separate implementation step.

A `FunctionCheckCase` declares an ordered list of `FunctionCallCheck` values.
Each call names a public entry and continuation, supplies full-width integer
arguments, and declares expected integer registers at the return boundary.
The checker installs the continuation in the return register before executing
the function. It pauses before executing the continuation. It does not run the
program's `main` or automatically execute its exit service.

Repeated calls within a case share memory, scratch registers and a cumulative
instruction budget. Different cases load the immutable image into independent
machines. The session factory must return a fresh caller-configured machine for
each case; the checker owns and closes it, including on failures or cancellation.
It never borrows an active IDE Run or Debug session.

For example, a public reversal exercise can request both distinct incoming links
and full-width saved-register preservation:

```kotlin
val calls = (0..255).map { byte ->
    FunctionCallCheck(
        entry = "reverse_and_invert",
        returnTo = if (byte % 2 == 0) "after_first" else "after_second",
        arguments = mapOf("a0" to (0x1234567800000000L or byte.toLong())),
        expectedRegisters = mapOf("v0" to ((Integer.reverse(byte) ushr 24) xor 255).toLong()),
    )
}
val cases = listOf(FunctionCheckCase(
    name = "reverse and invert every low byte",
    calls = calls,
    initialRegisters = mapOf("s0" to 0x1122334455667788L),
    preservedRegisters = listOf("s0"),
    helpers = listOf(FunctionHelperCheck("reverse_byte", "reverse_byte_return")),
))
val results = PreparedFunctionChecks.evaluate(image, cases, createSession, checkCancellation)
```

The named continuations must exist in the assembled program. When testing
different links, arrange the caller so executing a wrong continuation cannot
simply fall through to the requested one. A saved-link wrapper and a tail transfer
can satisfy the same behavior and preservation contract. No private scratch
register, exact instruction sequence, frame layout or exact instruction count is
required.

The checker initializes the declared registers once, then aligns the incoming
stack pointer downward before the first call (16 bytes by default). This is
external caller setup, not execution attributed to the student function. Each
call must restore that pointer and the incoming values of the declared preserved
registers. Arguments cannot overwrite the stack or return pointer; the host owns
entry/link preparation. Expected and preserved register aliases must be unique;
initialization cannot write register zero.

By default, 32 caller-owned bytes beginning at the aligned pointer are captured
before the calls and compared after every return. These are final byte comparisons
at the declared boundaries, not a claim that a restored intermediate write was
observed or that all other memory is protected. A profile may choose zero through
65,536 observed caller bytes and an alignment that is a power of two through 4096.
The source must support the chosen target and convention. The image restores its
target, memory and byte order before these operations.

Each declared helper interval is half-open: its first public label is included,
its end label is excluded. At least one actual completed instruction in every
interval must occur during each call. Reordered, missing or invalid labels cannot
earn helper credit. An increasing execution count is not an assertion of an exact
count or of the helper's source text. Results, preservation and the return boundary
are checked independently.

Function cases have no console input and require silent execution. They use the
existing bounded grading output and restricted syscall environment. An early exit,
trap, forbidden host service or exhausted instruction budget fails the named case.
The result envelope is the same `ConsoleCheckResult` used by existing formative
checks: a case name and optional failure, with `passed` derived from that failure.

Definitions are captured and revalidated before creating any machine, including
nested caller-owned maps and lists. A profile permits 1–256 uniquely named cases,
1–512 calls per case, at most 4096 calls overall, 1–1,000,000 instructions per case,
and at most ten million instructions overall. Register maps and preservation lists
have at most 32 unique registers; a case has at most 32 unique helper intervals.
Cancellation is checked before each case, during image loading, and before and
after each bounded call. Cancellation propagates rather than becoming a failed
student case.
