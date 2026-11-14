# MIPS64 source compatibility in MARS

## Source compatibility target

A student should be able to open, assemble, run, debug, and grade a self-contained
MIPS64 Release 6 user-mode assembly program in MARS. Use MIPS64 Revision 6.06 as the
reference for the scalar ISA and the MIPS64 SIMD Architecture specification for MSA.
A program may use MARS services or provide its own assembly routines for services it
needs. Loading ELF, linking arbitrary objects, and emulating an operating system,
privileged machine, or board are outside this scope.

MIPS64 includes the corresponding MIPS32 integer operations. Release 6 also removes
and reassigns some older instructions, so widening registers alone cannot make a
single decoder accept both generations. Use the Release 6 reference encodings for its native instructions. Preserve
classroom source behavior where possible. An instruction written as a mnemonic
can assemble to a different word after an upgrade.

[MIPS64 programming model, Rev. 6.01][vol1] ·
[MIPS64 instruction reference, Rev. 6.06][vol2] ·
[MIPS64 SIMD Architecture, Rev. 1.12][msa]

## Baseline in the repository

- The current [MARS64 catalog](../mars5/core/src/main/kotlin/edu/missouristate/mars/mips/instructions/WideInstructionCatalog.kt)
  adds 30 native instructions for doubleword arithmetic, shifts, rotates, leading-bit
  counts, multiplication and division, and loads and stores. It also supports
  `.dword`, and the CLI, debugger, and grader already handle full-width integer values.
- The shared scalar catalog contains older MARS behavior and selected Release 6
  instructions. For example, MARS keeps `addi` where Release 6 has `beqc`. Some
  doubleword instructions already use Release 6 encodings while MARS retains
  legacy delay-slot and HI/LO behavior elsewhere.
- [Effective addresses](../mars5/core/src/main/kotlin/edu/missouristate/mars/core/InstructionContext.kt),
  the program counter, symbols, and debugger addresses are still backed by the
  32-bit map. `ld` and `sd` currently require alignment. Scalar floating point uses
  paired registers for doubles, and word `ll`/`sc` lack a reservation model.
- [MARS64 tests](../mars5/core/src/test/kotlin/edu/missouristate/mars/MarsTargetTest.kt)
  already cover representative instruction words, register width, both byte orders,
  and reversal. They provide regression coverage for the current target.

## Target selection and encoding policy

Make Release 6 the native instruction set for MARS64. Replace conflicting
encodings and remove native forms that Release 6 dropped. Keep MARS32 as the
current project default while introducing the rewrite. Use one catalog for
target-specific availability and encodings, with shared implementations where
semantics agree. Check overlapping masks and distinguish intentional aliases
from ambiguity. Carry the selected target through assembly images, the CLI,
project and run settings, IntelliJ diagnostics, and grading.

Support older source spellings through MARS64 pseudo-instructions when the
expansion can preserve their behavior. For example, give `beqc` its Release 6
encoding and evaluate an `addi` pseudo against overflow and exception behavior,
temporary-register use, and instruction placement. Apply the same criteria to
legacy HI/LO, branch-likely, and unaligned merge forms.

When an automatic translation would alter behavior, the assembler should
diagnose the old form and explain what must change. IntelliJ should flag it
at the source, identify the Release 6 replacement or the required manual
rewrite, and offer a quick fix only when it can preserve behavior. Use the
same target and migration rules for CLI diagnostics, editor inspections, and
the grader. Test macro and include locations and verify that a quick fix
reassembles under MARS64.

Treat MSA as an optional instruction extension to the Release 6 target. Its enablement
must reach every caller that assembles the same project. Support common source-level
ISA and MSA directives where they fit the project model. Start with validation
and scoped assembler state. Add per-region decoding only if a concrete source
compatibility case requires it. Each execution image must have one unambiguous
meaning for any instruction word at a given address.

## Executable memory and encoding-dependent tools

Keep self-modifying code opt-in if the new decoder and memory model can
support it without duplicating execution machinery. Test runtime writes to
text, instruction fetch from data, and backstep of a modified instruction
against the active instruction set. Keep the setting off by default. If
retaining it requires a separate execution path or undermines correct memory
and undo behavior, document the concrete tradeoff before removing the
setting and its entry points. Continue to support encoding exercises using
assembled words, disassembly, and dumps.

Update binary and hex dumps, disassembly, encoding exercises, and golden test
values against Release 6. Make [X-Ray](../mars5/plugin/src/main/kotlin/edu/missouristate/mars/plugin/tools/xray/XRayView.kt)
decode using the active target. Show a clear indication when a diagram cannot
represent a particular instruction. Keep cached assembly images tied to the
target that produced them. Recheck instruction counts, label addresses, and
source stepping wherever a migration pseudo expands to multiple instructions.

## Implementation order

### 1. Inventory and conformance fixtures

Build a checked-in matrix from Volume II-A and the MSA reference. Track one row per
instruction form where operands, encodings, availability, or behavior differ. Record
the reference, target, implementation status, and test evidence for syntax, encoding,
execution, and debugger integration where relevant. Keep pseudo-instructions and
assembler directives in corresponding source-compatibility lists. Count explicitly
unsupported cases separately so the coverage figure has a clear denominator.

Classify baseline user-mode scalar instructions and MSA as implementation work.
For privileged instructions, cache or synchronization hints, and other optional
modules, record the concrete observable behavior and its dependencies. Model
hardware-independent operations where useful. A hint may be a documented no-op
only where that preserves the supported program's architectural behavior. Give
privileged and device-dependent instructions stable diagnostics. Evaluate other
published modules individually once the inventory shows whether their semantics
can be implemented in this execution environment.

Use GNU assembler and disassembler output as an independent encoding check with a
pinned toolchain and the matching ISA, byte order, and extension flags. Check in
small reproducible fixtures with their assembly source, expected instruction words,
and regeneration command. For deterministic execution, cross-check with a
reference emulator where available and run the checked-in expectations in CI.
The test harness may use reference executables outside MARS.
[GNU assembler MIPS options][gnu-as] · [GCC MIPS extension options][gcc]

### 2. Capture the current behavior before replacing the catalog

Inventory native forms, aliases, and pseudo expansions for both current targets.
Extend the existing encoding and execution fixtures and use the instruction lab
to check whole-catalog reachability. Separate the old encoding baseline from
observable semantics. Cover operand boundaries, word and doubleword results,
exceptions, control flow, floating point, memory effects, and backstep. Exercise
both byte orders where relevant. Record which tests will change intentionally
under Release 6. Finish the baseline before replacing instruction definitions.

### 3. Replace the native MARS64 instruction set

- Build the complete Release 6 user-mode scalar catalog family by family, then
  switch MARS64 to it. Isolate conflicting encodings and verify shared word
  instructions, full-width conditions and addresses, jump/link semantics, and
  Release 6 forbidden-slot behavior. Compare the new fixtures to the old
  behavioral baseline and explain each deliberate difference.
- Add missing forms such as `dext*`, `dins*`, `dlsa`, `dalign`, byte and bit
  permutations, `dahi`, `dati`, and `ldpc`. Reconcile multiply/divide, sign
  extension, overflow, and reserved fields against Release 6. Specify and test
  deterministic choices for results the reference leaves unpredictable.
- Add source directives and syntax in the order real examples need them.
  Cover local numeric labels, section/data/alignment aliases, common `.set`
  options, and symbolic address expressions such as applicable high/low fixups.
  Use self-contained multi-file assembly with existing symbols and includes.
  Diagnose syntax that requires a linker or runtime loader. Use freestanding
  compiler output as an additional fixture with explicit compiler flags and
  any assembly startup routines it needs.

### 4. Addressing, memory, and atomics

A genuinely 64-bit source target needs to preserve full-width pointers even with a
small backing store. Start with a sparse, bounded, flat 64-bit address map for the
R6 target and retain the established classroom layouts as defaults. Define mapped
ranges, permissions, stack/heap growth, and unmapped-address diagnostics. Preserve
all address bits through the PC, link registers, labels, assembler fixups, source
maps, debugger breakpoints and watchpoints, dumps, and grader APIs. Supply focused
fixtures above 4 GiB and near unsigned and segment boundaries. Keep 32-bit
instruction encodings as instruction words even when their addresses are wide.

Implement ordinary misaligned accesses for R6 as specified, with byte-order and
access-boundary tests. Specify which writes can commit when a multi-byte access
faults, and use that same contract in backstep and watchpoint reporting. Implement
word and doubleword reservation semantics for `ll`/`sc` and `lld`/`scd` in the
supported single-core setting. Test failed stores, reservation invalidation and
reset, alignment, and reversal. Add other user-mode atomic forms the matrix
identifies under the same model.
[Release 6 addressing and alignment rules][vol1]

### 5. Scalar floating point and MSA

- Introduce the architectural 64-bit FPR model for R6. Implement full-width moves
  such as `dmfc1`/`dmtc1`, applicable conversions, and the documented control and
  status state, including rounding and exceptional results where visible to
  user-mode code. Cover NaNs, signed zeros, subnormals, flags, and traps with
  bit-pattern oracles. Keep the MARS32 paired-register tests as a regression
  baseline.
- Implement MSA against the same session-local state model. MSA defines 32
  128-bit vector registers that share state with scalar floating point. Design
  around the specified scalar/vector aliasing before adding instructions.
  Start with a simple portable bit representation and explicit lane and byte
  ordering. Keep reuse at the level of operations that actually share semantics.
  [MIPS64 MSA reference][msa]
- Start vector implementation with loads and stores, register transfers and
  lane insertion/extraction, integer arithmetic and logic, shifts and comparisons,
  permutations and shuffles, saturation and fixed-point arithmetic, and
  floating-point operations. Track any remaining instruction forms in the
  matrix. Each stage covers every applicable lane width and relevant endianness,
  source/destination aliasing, control-state updates, and backstep.
- Expose native register syntax such as `$w0` through IntelliJ parsing,
  completion, highlighting, formatting, and documentation. Show the raw
  128-bit register value and selectable lane interpretations in Debug. Allow
  graders to assert vector bits and lane values. Use the existing debugger
  and grader interfaces as the entry points for these additions.

### 6. Add pseudo-instructions and source migration inspections

Once the native catalog is in place, provide `dli`, width-aware address and
register moves, and useful doubleword convenience forms. Evaluate removed
legacy instructions against the baseline. Add compatibility pseudos where a
faithful expansion is possible and inspections with manual migration guidance
where it is explicitly incompatible. Test expansion sizes, fixups, temporary
registers, source stepping, and macros. Show the actual instructions emitted
through the existing expansion and disassembly views.

### 7. Ship and qualify the target

Use the same catalog for assembler selection, CLI execution, disassembly, and
IntelliJ instruction metadata. Keep the IDE's native PSI and diagnostic paths.
Exercise each family with a representative runnable example, plus a few complete
programs. Candidates include a full-width integer routine and a vectorized
image or signal-processing loop with a scalar reference. Make the examples
runnable and debuggable in IntelliJ and gradable through the CLI.

Run Jupiter tests for assembly, encodings, simulator effects, and debugger
integration. Test state and fault behavior, including reversal. Count matrix
coverage separately from line and branch coverage. Continue the plugin's
existing coverage gates and exclude defensive branches only by justified
exception. Test the built plugin on the pinned IntelliJ version and use the
repository's clean launch tasks for each IDE smoke run.

The source-compatibility claim is ready when every in-scope scalar and MSA
instruction form has a tested disposition, the representative programs run in
the shipped CLI and IDE, and the published compatibility table identifies the
remaining differences. Document the supported dialect and extension flags,
the MARS syscall ABI, and any architectural behavior deliberately simplified
for a single-core educational runtime. Publish any unsupported directives or
instructions that would prevent a conforming source program with external
environment dependencies from running.

[vol1]: https://s3-eu-west-1.amazonaws.com/downloads-mips/documents/MD00083-2B-MIPS64INT-AFP-06.01.pdf
[vol2]: https://jnastarot.io/arch_index/pdf/MD00087-2B-MIPS64BIS-AFP-6.06.pdf
[msa]: http://iommu.com/datasheets/mips/isa/MIPS64_MSA-MD00868-1D-MSA64-AFP-01.12.pdf
[gnu-as]: https://www.sourceware.org/binutils/docs/as/MIPS-Options.html
[gcc]: https://gcc.sourceware.org/onlinedocs/gcc/MIPS-Options.html
