# MARS test coverage and remaining work

Updated 2026-11-08 at the Phase 2 completion boundary. The independent core release gate is complete;
this file records the evidence and the remaining edge-case/host-integration backlog. It supersedes the
initial characterization inventory, whose statements that these areas were untested are now obsolete.

## Verified coverage

| Area | Evidence |
| --- | --- |
| Basic instruction semantics | `BasicInstructionSemanticsTest`: all 155 registered implementations, explicit expected results and undo restoration |
| Encoding and decoding | `InstructionEncodingTest`: 155 independent operand/encoding vectors and decoder checks; reserved syscall/trap code-field defect fixed |
| Pseudo instructions | `PseudoExpansionTest`: every one of the 388 forms, three layouts, both branch modes; 2,328 exact expansions compared with the pre-refactor pipeline |
| FP arithmetic and rounding | `FloatingPointEdgeTest`: 560 special-value and rounding cases; `Coprocessor1StateTest` covers register pairs and flags |
| Memory | `MemoryStateTest`, `RuntimeServicesTest`, and legacy memory tests: all layouts and segment families, text/data/MMIO exclusive limits, odd halfword offsets, sparse block boundaries, byte order, heap failures, self-modifying code and observers |
| Assembly/source ownership | `SessionAssemblyTest` in the compatibility integration suite: independent and concurrent assemblies, cross-file forward data references, exported/local symbols, nested includes, macros, `.eqv`, source diagnostics, budgets and stale handles |
| Independent core operation | `CoreSessionIntegrationTest`: corpus assembly and concurrent program execution without compatibility classes on the classpath |
| Undo | Every basic semantic vector plus `SessionBackStepperTest`: instruction frames, loops, delay slots, FP pairs, flags, faults, all memory write widths, heap, bounded-history eviction and recovery after restore failure |
| Execution controls | `SimulatorEngineTest`: worker isolation, step limits, breakpoints, pause/resume, stop, undo, event ordering, faults, new programs and conflicting mutation rejection |
| Interrupts | `RuntimeServicesTest`: session-private pending interrupt, exception-handler dispatch, retry and `eret` |
| Syscalls | `SyscallContractTest`: all 40 currently registered services; further file/console/random/MIDI ownership and failure checks in the syscall integration tests |
| Settings | Reconciliation tests cover persistent versus session-only simulation settings; frontend test checks font/color persistence through `EditorSettings` |
| Packaging | Core-only CLI integration tests assert the compatibility facade cannot be loaded; runnable CLI and legacy frontend jars are built and smoke-tested |
| Performance | JMH `AssemblerBenchmark` assembles 10,000 instructions; longer/profiling runs are available separately from correctness tests |

Original smoke tests remain intact as characterization tests in `legacy/src/test`; they exercise the
canonical compatibility session. Core's own tests have no dependency on that module. Test fixtures
are shared as resources, not through a runtime dependency on Venus.

## Further testing and feature decisions

These are additional coverage or product decisions, not hidden dependencies required to use the core
without Venus. Keep them visible when extending the corresponding subsystem:

- Add property/fuzz tests for arbitrary instruction operands, all combinations of mixed unaligned
  word operations and every named memory address around all layout boundaries. Current deterministic
  vectors and selected boundary tests establish the modernization gate, not exhaustive ISA proof.
- Expand invalid or canceled dialog input, host I/O failure, and unusual Unicode input cases beyond
  the existing positive service vectors and file/console failure checks.
- Run interactive Venus smoke tests on supported desktop platforms and physical MIDI-provider tests
  separately. Automated tests verify the frontend adapters and MIDI routing without opening windows
  or audio devices.
- Add longer concurrent lifecycle stress tests and tests involving a host I/O provider that blocks
  indefinitely. Pause/stop are defined at instruction boundaries; the simulator does not forcibly
  cancel arbitrary host I/O in the middle of a syscall.
- Extend platform packaging checks across Windows and Linux. The CI workflow runs the full build on
  Linux/Java 25; its first remote result is pending the next push.
- Add more tests for legacy CLI flag combinations and optional third-party tool loading if those
  frozen interfaces are changed. The new standalone CLI documents its supported options explicitly.

The original inventory mentioned a writable FCSR, `.set`-driven assembler modes, and a
`Syscall.properties` override file. Those do not describe capabilities implemented by the current
runtime: FP rounding is selected by instruction opcode, unsupported directives retain the existing
warning/ignore behavior, and the current syscall inventory has 40 registered services. Adding those
features requires a separate semantic/product decision, rather than asserting tests for behavior the
legacy simulator did not implement.

MARS keeps little-endian memory as its default. Address and byte-order behavior was preserved during
the sparse-segment extraction. The one intentional machine-code correction in this phase zeros the
reserved `c` fields of syscall/trap masks; passing letters through the binary parser had corrupted
those encodings. Both source execution and the encoding/decoding regression checks pass afterward.
