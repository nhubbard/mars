# Prepared function checks

`PreparedFunctionChecks` evaluates bounded function-call contracts against an
existing `AssemblyImage`. It is the data-only counterpart of the assignment DSL's
`enterFunction`, `runUntil`, register assertions, caller-byte comparisons and
execution traces. It does not evaluate an assignment script or change the ISA.
Native lesson format 6 exposes this checker through an explicit function
assessment kind. See [the native content contract](native-lesson-format.md#format-6-function-assessments)
for the bounded JSON profile and its existing Learn controls.

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

## Functions that return results in memory

`FunctionCallCheck.argumentAddresses` passes the submitted assembly's actual data
addresses. Each `FunctionDataAddress` names a public data label and an optional
signed byte offset. These arguments are separate from integer literals and share
the same unique-register validation: neither form can initialize zero, `sp` or
`ra`, or supply two aliases for one register. A moved data allocation therefore
does not change the contract.

`FunctionCheckCase.initialMemory` prepares exact bytes before execution, once per
fresh case. `FunctionCallCheck.memory` compares exact bytes after that invocation
returns. Both use `FunctionMemoryCheck(label, bytes, offset)`, with hexadecimal
byte pairs in increasing address order. This is a byte contract, not an implicit
host-endian word encoding. Authors must encode numerical words for the intended
image byte order. An offset defaults to zero and is bounded to plus or minus one
MiB. Data labels must resolve to accessible user data in the assembled submission;
a missing label, a text label or an inaccessible byte fails the named case.

For example, a renderer can accept a source and frame pointer, preserve incoming
saved registers, and return with a known byte pattern in the destination without
promising any value in `v0`:

```kotlin
FunctionCheckCase(
    name = "overwrite a prefilled destination",
    calls = listOf(FunctionCallCheck(
        entry = "render",
        returnTo = "after_render",
        arguments = emptyMap(),
        expectedRegisters = emptyMap(),
        argumentAddresses = mapOf(
            "a0" to FunctionDataAddress("source"),
            "a1" to FunctionDataAddress("frame"),
        ),
        memory = listOf(FunctionMemoryCheck("frame", "00000000ffffff00")),
    )),
    initialMemory = listOf(FunctionMemoryCheck("frame", "5a5a5a5a5a5a5a5a")),
)
```

At least one expected register or memory range is required for every call. A call
admits at most 64 expected ranges; a case admits at most 64 setup ranges. Individual
ranges and the sum of setup bytes plus all per-call expected bytes are bounded to
one MiB per case. Setup ranges are applied in declared order; overlapping setup
bytes take the last declared value, as successive setup writes in the assignment
DSL do. Expected ranges are independent assertions. All nested lists and address
maps are captured and revalidated before opening a session.

These checks compare bytes at return boundaries. They do not require a particular
store width, prohibit temporary writes that are later restored, or establish a
complete write history. The caller-stack comparison and optional helper traces
remain separate obligations. The label-relative `AssemblyCase.setBytes` overload
uses the same stopped-session byte editor as absolute-address setup and preserves
unmentioned bytes of partial words.

## Protecting input objects from execution stores

A case may declare `noStores: List<FunctionNoStoreCheck>`. Each item names a public
data label, a positive byte length and an optional signed byte offset. The checker
starts the existing `MemoryStoreTrace` observers after initial memory preparation
and before the first function call. After every return, each protected region
must have received no execution store of any width. Same-value writes, temporary
writes followed by restoration, and wider stores starting outside but overlapping
the region all fail. Application-origin setup and inspection are not simulated
execution stores.

These observers span every call in that case and close with its existing
`AssemblyCase` lifetime, including failures and cancellation. At most 64 regions
and a combined one MiB of protected spans are allowed per case. Label and offset
validation is the same as for other function data references. The definitions are
captured and revalidated before any grading session is created.

For example, `FunctionNoStoreCheck("endpoints", 16)` protects a four-word input
record while allowing a student to reorder its values in working registers. A
raster may use a different algorithm and store its output cells in a different
order; protection does not prescribe those implementation choices. Returned
values and final output words remain separate assertions. Declared protected
regions do not constitute a whole-address-space write audit. Caller-stack bytes
continue to use the separately documented after-return comparison.
