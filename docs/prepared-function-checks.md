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

## Restricting observed accesses and caller stack stores

`FunctionCheckCase.accessBounds` accepts immutable `FunctionAccessCheck` values.
Each definition names a data label, watched byte length, permitted byte offset,
permitted byte length, and optional watched offset. Both offsets are relative to
the **label**, not to each other. The permitted subwindow must fit wholly inside
the watched window. A zero-length permitted window allows no observed accesses;
the watched length must be positive. Labels and offsets follow the existing
function data-address rules. At most 64 watched regions with a combined one MiB
are accepted per case, separately from the existing protected-store regions.

For example, `FunctionAccessCheck("values", 40, 8, 16, offset = -4)` watches a
32-byte array and its adjacent four-byte guards, while permitting reads and
stores only to its four active words at offsets 8 through 23. Every completed
execution data access overlapping the watched window must fit wholly inside the
permitted subwindow. A wider access beginning outside the watched window is
still checked if it overlaps. This reuses `MemoryAccessTrace`; instruction fetch,
tool inspection and backstep do not supply forward execution data notices.
The default 65,536-notice limit applies per trace. Overflow fails the case instead
of accepting a truncated history. The observation is bounded: accesses that do
not overlap a watched window remain outside this particular assertion.

`protectCallerStack = true` additionally rejects execution stores overlapping
the `callerStackBytes` starting at the caller's initially aligned stack pointer.
Same-value and later-restored stores fail. When `callerStackBytes` is zero there
is no protected caller window. Private frames below the initial pointer remain
allowed. The existing after-return comparison and stack-pointer checks still
apply; the default `false` retains their original comparison-only behavior.

All observers begin after setup and span the calls in one case. They close with
the existing `AssemblyCase`, including failure and cancellation. New definitions
are copied and revalidated before a session opens. These checks do not prescribe
a private frame layout, temporary registers, algorithm, or order of valid stores.

Failures during a call identify its one-based index, entry and continuation,
followed by supplied scalar arguments and the original failure detail. Arguments
come from the captured definition, so a callee that overwrites an argument
register cannot replace the reported counterexample. Cancellation still
propagates rather than becoming a failed student case. Case-setup failures before
a call retain their original setup diagnostic.

## Checking a mapped element transfer

`FunctionCheckCase.transfers` accepts `FunctionTransferCheck` data. Each definition
names a source and destination `FunctionDataAddress`, a destination index for
each source element, and `elementBytes` (one, two or four; default four). The
index list must be a permutation of zero through element count minus one. Empty
lists describe empty active windows. At most 32 maps, 65,536 elements per map,
and one MiB of combined active source/destination bytes per case are accepted.

For example, source indices `[0,1,2,3,4,5]` in a two-by-three matrix rotate
clockwise using destination indices `[1,3,5,0,2,4]`. The checker requires exactly
one aligned element-sized execution read at each source element and exactly one
aligned element-sized execution write at each destination element. It rejects
source writes, destination reads, duplicate visits, incomplete visits, partial
or crossing notices and a destination write before its mapped source read.
Reading every source first, or copying in another valid order, is accepted.
The checker does not require private labels, scratch registers or a tile order.

These are **data-notice** contracts. MARS currently emits two word notices for
an aligned doubleword load/store; a word map can observe those as two elements.
The check does not infer an instruction mnemonic or require one instruction per
element. It does not compare the transferred values; declare expected final
memory separately. Pair it with `accessBounds` covering the allocations to
reject inactive traffic, including an empty window's first accidental read.
Traffic outside the two active windows belongs to those separate checks.

Resolved source/destination addresses must be aligned, nonnegative,
non-overflowing and nonoverlapping. Invalid assembled addresses fail the source
contract. Streaming Boolean arrays retain only bounded visit state and the first
failure; no full access history or cache model is needed. Visit state resets for
each call, so a second call cannot inherit the first call's traffic. Setup and
external inspection do not count. Observers detach on return, failure and
cancellation; execution, memory values and backstep semantics are unchanged.
Nested map lists are copied and revalidated before any machine opens.

Native function-profile **format version 5** adds optional `transfers`:

```json
{
  "source": {"label": "input", "offset": 4},
  "destination": {"label": "output", "offset": 12},
  "destinationIndices": [1, 3, 5, 0, 2, 4],
  "elementBytes": 4
}
```

Place this object in the case's `transfers` array. Offsets are bytes relative to
assembled data labels. Version 5 retains version 4's access and caller-stack
checks. Older versions reject the new field; an omitted or empty array retains
the earlier behavior. Content remains bounded data and cannot run a script.

## Comparing borrowed and stored addresses

Native function-profile **version 6** adds `expectedAddresses` to a call. It maps
integer result registers to `FunctionDataAddress` values, resolving each label and
byte offset from the submitted image. For example, `"v1": {"label":"input",
"offset":8}` checks the returned borrowed payload pointer without assuming a
particular load address. Numeric and address expectations cannot name the same
register, including aliases. A call can declare only an address result, using an
empty `expectedRegisters` object, or combine addresses with numeric and byte results.

`addressMemory` checks an eight-byte native-endian stored pointer. Each item is
`{"at":{"label":"payload_address"},"value":{"label":"input"}}`.
The grader resolves both addresses and uses the captured image's byte order;
it never dereferences the expected pointer. This distinguishes a complete pointer
store from truncation at addresses above four GiB. The same definition works after
relocation and with either byte order. Address-memory and literal-memory results
share the 64-range / one-MiB limits, and all calls' expected bytes count toward the
existing combined case budget. Missing data labels are source-check failures.

## Restricting stores across an assembled caller layout

Version 6 also adds case-level `storeBounds`, using `FunctionStoreBoundsCheck`:

```json
{
  "from": {"label":"input_guard"},
  "until": {"label":"state_end","offset":4},
  "permittedFrom": {"label":"line_a"},
  "permittedLength": 8
}
```

The watched interval is half-open: `from` is included and `until` excluded.
Every execution store overlapping that interval must fit wholly in the permitted
window. Reads are not restricted by this assertion. Omit `permittedFrom` and
`permittedLength` to forbid all watched stores; a zero permitted length has the
same effect. Use ordinary `accessBounds` for permitted input reads and `noStores`
for separately protected objects. Temporary or same-value forbidden writes fail,
including writes into unnamed alignment padding. A wider crossing store is checked
as a whole notice rather than only its starting address.

Both endpoints and the permitted address resolve from data labels and bounded
offsets. This avoids assuming the distance between caller objects. Reversed,
empty or oversized assembled watch intervals fail the source contract. At most
64 windows and one MiB of combined resolved watched bytes are accepted per case.
Permitted lengths are bounded to one MiB; a nonzero length requires an address.
The permitted window can lie outside the watched interval, as when a correct
submission reorders a selected record. This assertion still governs only stores
that overlap its watch interval; other protections remain necessary.

The implementation reuses `MemoryAccessTrace`, with its complete 65,536-notice
history limit and explicit overflow failure. Reads contribute to that bounded
history even though only stores are checked here. Setup, debugger inspection and
instruction fetch do not count. Histories span the calls in a case, while existing
instruction limits still bound execution. The owning `AssemblyCase` detaches all
observers on completion, failure or cancellation. Nested result maps and lists are
captured and revalidated before a machine opens. Older profile versions reject
these new fields instead of silently weakening their meaning; version 6 without
them retains the earlier behavior. No executable script is supplied by this data.

Version 6's `noExecution` case field declares up to 32 distinct half-open
instruction intervals, using the same `{ "from":"label", "until":"label" }`
data as `helpers`. Every call must complete without executing an instruction in
any such interval. The grader compares completed instruction counts before and
after the call. Stopping at the interval's first instruction does not execute it.
This can distinguish a correct return to a supplied continuation from jumping to
an earlier continuation and falling through to the requested stop. It imposes no
private helper names or order beyond the explicitly declared public interval.
Missing/reversed assembled intervals fail as source contracts; duplicate or
excessive definitions reject before execution. Existing helper-presence checks
and all prior formats retain their behavior.

## Initializing an output before reading partial sums

Native function-profile **version 7** adds case-level `initializedOutput`, using
`FunctionInitializationCheck`. Versions 1–6 reject this field. Version 7 retains
all earlier fields, including version 6's address results and store bounds.

```json
"initializedOutput": [
  {"at":{"label":"output","offset":8},"length":16}
]
```

Each byte in the assembled range must receive an execution store during every
call, before its first execution read. Later reads and repeated stores are
allowed. This accepts register accumulators, zero-then-accumulate schedules,
different byte widths and traversal orders. It rejects a partial-sum load from
old caller data even if a later repair produces correct final bytes. Every byte
must be written even when its original value already equals the expected result.
Check final values separately with ordinary memory results.

The observation resets before each call. Setup writes, debugger inspection,
instruction fetch and prior calls do not initialize the current call's output.
An access crossing a range edge affects only its intersecting bytes for this
check; use access/store bounds separately to enforce ownership of the whole
access. Native split notices for doublewords initialize all affected bytes.
The check does not prescribe an instruction count, cache count or private frame.

Ranges use the existing assembled data-label and bounded-offset contracts. Their
lengths may be zero for empty outputs. At most 64 ranges with a combined one MiB
of bitmap storage are allowed per case. Missing data labels and overflowing
resolved intervals fail as source contracts. Immutable definitions are captured
and revalidated before a machine opens. Streaming byte bitmaps avoid retaining
an additional access history; existing cumulative instruction limits still apply.
All observers detach on completion, failure or cancellation. This is bounded
data interpreted by the grader, not an executable predicate.

## Access policies over an assembled caller layout

Native function-profile **version 8** adds case-level `accessPolicies`, using
`FunctionAccessPolicyCheck` and `FunctionDataWindow`. It retains all earlier
fields; versions 1–7 reject the new field.

```json
"accessPolicies": [{
  "from":{"label":"matrix_guard"},
  "until":{"label":"completed","offset":4},
  "readable":[
    {"at":{"label":"matrix","offset":8},"length":24},
    {"at":{"label":"vector"},"length":12},
    {"at":{"label":"output"},"length":16},
    {"at":{"label":"shape"},"length":8}
  ],
  "writable":[{"at":{"label":"output"},"length":16}]
}]
```

Every execution data notice overlapping the half-open assembled interval must
fit wholly within one permitted window of its access type. This covers unnamed
alignment padding without assuming a fixed distance between caller objects.
An omitted or empty `readable`/`writable` array permits no corresponding access.
Zero-length windows likewise permit no traffic. A notice spanning two adjacent
windows must still fit within one window; the checker does not merge them.
Windows may lie outside the watched interval, as when a submission reorders
caller data. Traffic not overlapping a watched interval remains outside this
assertion, including private stack use. This is not a whole-address-space policy.

Endpoints and windows resolve from data labels and bounded offsets. A watched
interval must contain one through one MiB of bytes; at most 64 intervals and
one MiB of combined resolved watched bytes are allowed per case. Each policy
permits at most 64 windows across both access types, and their combined byte
lengths across a case are bounded to one MiB. Missing data labels, reversed or
empty assembled watch intervals and overflowing permitted ranges fail as source
contracts. Definitions are deeply captured and revalidated before opening a
machine, including caller-owned mutable window lists.

The implementation reuses the existing execution-only `MemoryAccessTrace`.
Histories span repeated calls and fail explicitly above 65,536 native notices;
they never silently drop later accesses. Setup, host inspection and instruction
fetch do not count. The owning assignment closes traces on success, failure or
cancellation. Use `initializedOutput` to require current-call initialization
before partial-sum reads, and memory results to check arithmetic separately.
