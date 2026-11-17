# MARS execution profiling

The workload is the MARS64 PNG decoder running with MSA on `torture.png`. It
exercises integer, branch, memory, syscall, and vector execution. The core
benchmark checks normal exit, the PNG success message, and all 53,035,658
completed instructions on every iteration. The PNG smoke suite separately
compares the framebuffer with the expected pixels.

## Reproduce

From the repository root, use JDK 25 for the core benchmarks.

```sh
./mars5/gradlew -p mars5 :core:benchmarkExecution \
  -PcoreExecutionRecording=/tmp/mars-core.jfr
./mars5/gradlew -p mars5 :core:benchmarkExecution -PexecutionBenchmarkMode=sync
```

The asynchronous mode uses the simulator worker and a step-count listener.
The synchronous mode uses the CLI's execution entry point. Each mode measures
one warmup and three fresh sessions in the same JVM. Timings cover execution
through stop, excluding assembly, image load, argument setup, and disposal.

From `mars5`, run the packaged IntelliJ replay with the optional PNG
workload and recording enabled.

```sh
./gradlew clean cleanSandbox runIde -PpackagedSmoke \
  -PexecutionProfile=/tmp/mars-ide.jfr
```

The replay runs the PNG fixture three times and pauses a Debug launch at
the start of the decoder before resuming it. The sandbox log includes
`packaged-smoke-trace.json` and `packaged-smoke-SHA256SUMS`. Use the
`runRunConfiguration` span for process-starting to exit and filter JFR
execution and allocation samples to threads named `mars-simulator`.
The JFR files can be examined with the JDK's `jfr print` command.

```sh
jfr print --events jdk.ExecutionSample,jdk.ObjectAllocationSample /tmp/mars-ide.jfr
```

## Core results

Medians on arm64 macOS with JDK 25.0.1. Both asynchronous runs recorded JFR
with the `profile` settings. Both synchronous runs used regular timing.

| Core execution | Before | After | Wall time reduction |
| --- | ---: | ---: | ---: |
| Asynchronous | 5.326 s | 3.934 s | 26% |
| Synchronous | 4.093 s | 3.365 s | 18% |

The baseline had a register-file forwarding listener on every register,
even when the register group had no subscribers. Making the group emitter
explicit lets a register skip notice construction when both subscription
lists are empty. The new path keeps group listeners ahead of local listeners
and preserves the local subscription snapshot for the current emission.
Register access notices were the largest sampled allocation class in the
baseline and disappeared from the asynchronous workload's sampled
allocations after the change.

The core also reuses the immutable scoped binding for execution provenance
and checks for a delay or forbidden slot before classifying an instruction
as a control transfer. The binding still starts and ends at the instruction
boundary. Listener callbacks and exceptions retain their existing scope.

## IntelliJ results

In the baseline IDE recording, the Run process handler's step callback
appeared in 1,536 of 3,671 simulator-thread execution samples. Catalog
decoding appeared in 769 samples, and branch history appeared in 401.
These counts overlap. The three baseline Run spans were 16.589, 16.413,
and 16.457 seconds. The baseline replay reached the PNG Debug setup
after all three Runs. The editor-backed bitmap view had taken focus,
and playback's caret command could not address an assembly editor.
The replay now installs the breakpoint by source path and line.

The Run and Debug process handler uses a fixed-size cache per launch.
Instruction Counter, Instruction Statistics, and Branch History share
canonical decoding by the full executed machine word. Branch History adds
the cached offset to the current full-width PC. A changed machine word
gets its own decode, and a slot collision simply replaces the cached word.

The first optimized replay measured 10.838, 10.013, and 10.377 seconds
for Run, a median of 10.377 seconds versus 16.457 seconds before the
changes. The comparison covers the combined core and plugin changes.
The step callback appeared in 611 of 2,333 simulator-thread execution
samples from those three Runs. Both IDE recordings used IntelliJ IDEA
2026.3 and JetBrains Runtime 25.0.4.1 with the same JFR settings.
The first optimized replay paused at the PNG breakpoint and then stopped
on an editor-focus assertion. The replay now uses IntelliJ's breakpoint
pause confirmation directly.

The corrected clean replay measured 11.057, 10.237, and 10.481 seconds.
Its median Run time was 36% below baseline. It confirmed the PNG breakpoint,
resumed execution through debugger termination, and verified the standard
packaged Run, Debug, and FIR outputs. The tool callback appeared in 643 of
2,383 simulator-thread execution samples during the three PNG Runs.

## Remaining execution costs

- Backstep setup appeared in 388 of 2,333 optimized IDE samples. The
  allocation profile still shows delayed-branch snapshots and checkpoint
  closures. A follow-up can compare representing common undo states
  without per-instruction allocation while testing device checkpoint
  ordering, reservations, and fault and delay-slot backstep.
- Memory access notices were the largest sampled allocation class in
  the optimized IDE runs. X-Ray ignores instruction-fetch notices.
  A follow-up can measure fetch-aware subscriptions that preserve
  delivery for the tools and watchpoints that use fetches, and simplify
  X-Ray's separate map of events to memory accesses.

The verification suite passed 2,413 core, 57 CLI, 879 plugin, and five
PNG smoke tests. The instruction lab executed 867 basic forms and 430
pseudo-instruction forms. Plugin coverage gates passed.
