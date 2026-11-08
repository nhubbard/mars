# Phase 2 completion — independent MARS core

Status: Phase 2 completed on 2026-11-08. A subsequent Phase 2.5 legacy-deprecation cleanup now
precedes Phase 3, IntelliJ plugin implementation; see [the Phase 2.5 plan](PHASE_2_5_PLAN.md).
No Phase 3 language support, tool windows, or IntelliJ SDK integration has been started.

## Delivered architecture

| Module | Responsibility | Runtime dependencies |
| --- | --- | --- |
| `mars5/core` | Assembly, instruction decoding and execution, hardware, undo, typed events, public frontend APIs | Dagger and injection APIs; no legacy module, Swing, AWT, or Venus |
| `mars5/cli` | Standalone headless launcher and console integration | Core |
| `mars5/legacy` | Deprecated global and static adapters for the frozen frontend, its old simulator controls and global I/O | Core |
| `mars5/plugin` | Existing Venus frontend and tools, editor font/color adapters and file-chooser integration | Legacy and core; IntelliJ implementation comes next |

Every module compiles for Java 25. The build uses Gradle 9.9. The earlier root-only toolchain setting
has been extended to subprojects; published module classes use class-file major version 69.

The compatibility module retains the old Java entry points needed by Venus. Its register, memory,
and coprocessor facades resolve to one canonical Dagger-owned session. The live frontend proxy
continues to work when Venus is attached after the legacy runtime was initialized. Compatibility
code and its regression tests are outside core; core's own tests run without those classes present.

## Core ownership and API

One `MarsComponent` owns one `MarsContext`, including settings, integer registers, both coprocessors,
branch state, memory layout and contents, heap, symbol tables, current program, undo history,
random streams, syscall implementations, and default I/O services.

- `MarsComponent.assembler()` implements the `MarsAssembler` contract. Successful results preserve
  warnings; failed results retain source locations and have no program handle.
- `MarsComponent.simulator()` implements `MarsSimulator`. A session's `SimulatorEngine` serializes
  execution and undo on one executor and publishes step, stop, and error events. Run, step, pause,
  stop, breakpoint limits, backstep notifications, faults, and independent workers are tested.
- `MarsContext.assemble(...)` and the returned program's synchronous methods support batch use.
  Both controllers use the same single-instruction execution path.
- Overlapping assembly, layout changes, execution, and undo are rejected instead of mutating an
  active session. Stale program handles cannot execute against a replacement assembly.
- Default syscall I/O uses session-private file descriptors. Console streams may be supplied by a
  host; caller-supplied environments remain caller-owned. Assembly/reset/close releases default
  resources without closing another session's files or the process console streams.
- Syscall implementations, random stream numbers, external interrupt requests, and MIDI dispatch
  belong to the session. MIDI workers are owned by the environment; the retained device-open lock
  protects the host MIDI provider and carries no simulation state.

Callbacks run on the simulator worker. A frontend must dispatch its UI work to its own UI thread.
Pause and stop take effect at an instruction boundary. Arbitrary host I/O performed by an instruction
is not reversed by backstep. Direct hardware access is intended for stopped execution; the controller
serializes its own mutations and assembly operations.

## Structural refactors

- The basic instruction catalog is resource-driven. Each of the 155 registered implementation
  classes keeps its encoding metadata and simulation function together; a loader reads the catalog
  and existing pseudo templates. Missing/malformed resources throw an exception instead of exiting
  the embedding process. Keeping metadata with each implementation avoids duplicate definitions.
- `Assembler` orchestrates `FirstPassProcessor`, `Parser`, `PseudoInstructionExpander`, and
  `SecondPassProcessor`. Directive and symbol state stays in the first pass; macro syntax, template
  expansion, code emission, and duplicate-address checks have explicit responsibilities.
- `MemoryState` coordinates address translation, permissions, heap and notifications. Sparse word
  and text storage are separate `WordMemorySegment` and `TextMemorySegment` classes. Data, kernel,
  stack, and MMIO share tested word storage; descending stack traversal remains explicit.
- Settings persist plain font specifications and color integers. The frontend constructs desktop
  objects through `EditorSettings`. File chooser adapters and font utilities live outside core.
- The old custom Swing worker has been removed. Both the modern controller and the legacy simulator
  use Java executors; Venus supplies its completion dispatcher.
- Instruction formats are an enum. The core compiles with `-Xlint:rawtypes`, `-Xlint:unchecked`, and
  `-Werror`. `verifyCoreIsolation`, attached to `check`, rejects desktop and compatibility imports.

## Correctness evidence

- All 155 registered basic implementations have explicit semantic vectors, including restoration
  of affected registers, coprocessors, flags, memory, and PC through undo.
- All 155 have independent operand/encoding vectors and binary-decoding checks.
- 560 FP cases cover supported arithmetic, conversions, comparisons, special values, signed zero,
  denormals, infinities, NaNs, and opcode-specific rounding conventions.
- Every one of the 388 pseudo forms is compared against expansions captured from the previous
  assembler at `e48d379`, across all three memory layouts and both delayed-branch modes: 2,328
  exact machine-word sequence comparisons. Reserved code bits are normalized as described below.
- All 40 currently registered syscall services are exercised against a supplied headless environment.
  File modes, descriptor limits, binary round trips, ownership, random interleaving, MIDI routing,
  dialogs, heap allocation, and process exit are covered by further integration tests.
- Existing characterization tests remain in `legacy`; independent core tests cover the assembly
  corpus, concurrent components, exceptions, interrupts, limits, breakpoints, and undo without the
  compatibility classes on their runtime classpath.
- A frontend test checks persisted font/color conversion through the editor adapter. No interactive
  Venus window or physical MIDI device is required by the automated suite.

The registry contains 155 basic implementations and 40 syscalls. The original plan's approximate
178/45 counts are not the inventory of this repository; no unsupported instructions were invented
to satisfy those estimates.

### Encoding defect found during validation

Reserved `c` fields in syscall/trap instruction masks were passed as letters to the binary parser.
That produced `0x7fffffcc` for `syscall` and could contaminate trap operand bits. The encoder now
zeros those reserved fields before converting the mask. `syscall` encodes as `0x0000000c`; every
basic vector assembles and decodes correctly. Source-level execution and the characterization suite
continue to pass. This is an explicit correction to invalid machine-code output, not a hidden fixture
update to accept the old defect.

## Recorded acceptance result

The final local build passed all **1,186 tests**, with zero failures, errors, or skips:

| Suite | Tests |
| --- | ---: |
| Independent core | 1,036 |
| Legacy compatibility/characterization | 147 |
| Standalone CLI | 2 |
| Frontend editor adapter | 1 |

The CLI jar ran Hello World on Java 25; the legacy frontend jar assembled that program with
`java.awt.headless=true`. Archive inspection confirmed that the CLI contains no compatibility
facade. All module class files have major version 69. The Java 25 JMH smoke run measured
**158.997 ms/op** for the 10,000-instruction assembly benchmark (one short warmup/measurement run).

## Reproduce acceptance

Run with JDK 25 available to Gradle:

```sh
cd mars5
./gradlew build :core:benchmarkClasses --offline --no-daemon
./gradlew :core:benchmark --offline --no-daemon
java -jar cli/build/libs/cli-1.0-SNAPSHOT.jar --help
java -jar cli/build/libs/cli-1.0-SNAPSHOT.jar core/src/test/resources/testPrograms/01_helloworld.asm
java -Djava.awt.headless=true -jar plugin/build/libs/plugin-1.0-SNAPSHOT.jar nc a core/src/test/resources/testPrograms/01_helloworld.asm
```

Omit `--offline` on a machine that has not downloaded dependencies. The CI workflow builds and tests
all modules and compiles the JMH benchmark on Java 25. Offline builds have been verified locally;
the workflow will run remotely on the next push.

The JMH benchmark assembles a 10,000-instruction program. Its short local measurement is a smoke
baseline, not a statistically established performance target; use longer runs and consistent JVM
settings for comparisons.

## Phase 3 handoff

Begin by choosing an IntelliJ Platform SDK compatible with the project's Java 25 target and wiring
the plugin composition root to `MarsComponent`. Language/PSI support, highlighting, completion,
reference resolution, diagnostics, Run/Debug configurations, console/register/memory tool windows,
and debugger integration are the next planned work. Frontends should use the core contracts directly.
The legacy module can be removed together with Venus after its replacement is functional.

Further edge-case and host-integration testing remains documented in `mars5/TEST_GAPS.md`. The old
backlog mixed supported-behavior tests with features absent from MARS, such as a writable FCSR and
external syscall-number configuration. This phase preserves the supported runtime rather than
silently adding those features. Interactive Venus and physical audio QA remain distinct from the
verified headless/core acceptance gate.
