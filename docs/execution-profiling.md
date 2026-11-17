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

## Backstep follow-up

Backstep clears the common absent load-linked reservation directly on undo,
with the same ordering before device restore callbacks. A live reservation
is still restored from its captured value. The branch checkpoint reuses
immutable snapshots when there is no pending transfer, and the undo ring
wraps its indices without integer remainder.

On the same asynchronous PNG workload, a paired run with a fixed 1 GiB heap
measured a 3.929 s baseline median and optimized medians of 3.863 and 3.897 s
in separate JVMs. The timings are close enough to treat this as an allocation
improvement. Separate JFR recordings of the four launches in each revision
estimated 7.27 GB and 1.52 GB of cumulative delayed-branch snapshot allocations.
The optimized profile also dropped the per-instruction absent-reservation
closure from the leading allocation sites. The figures are sampled cumulative
allocations, rather than resident memory.

## Memory observer follow-up

Address subscriptions can now exclude instruction fetches before constructing
a notice. The existing single-argument subscription continues receiving all
accesses. Registration publishes a snapshot containing both the complete list
and its fetch subset, preserving subscription order and delivery during
callbacks that change registration. Range observers construct a notice only
after finding a matching range.

X-Ray, the data-cache simulator, and debugger memory watchpoints use data-only
subscriptions. Memory-reference displays continue receiving instruction fetches.
The data-cache recorder also retains its direct-call fetch filter.

To compare delivery modes with the same checked workload, run both commands.

```sh
./mars5/gradlew -p mars5 :core:benchmarkExecution -PexecutionBenchmarkObserver=all
./mars5/gradlew -p mars5 :core:benchmarkExecution -PexecutionBenchmarkObserver=data
```

Both modes assert exactly 9,004,117 delivered data accesses and 53,035,658
completed instructions per launch. A three-sample comparison measured median
execution times of 4.024 s with broad delivery and 3.874 s with data-only
delivery. This compares subscription modes on the same core revision.

The clean IntelliJ replay measured 9.891, 9.630, and 9.440 seconds for
PNG Run, giving a median of 9.630 seconds compared with 10.481 seconds in
the preceding qualified replay. This 8% reduction includes the earlier
backstep follow-up as well as the observer changes. Across the three Run
spans, JFR sampled cumulative memory-notice allocations of 1.15 GB, down
from 9.37 GB in that preceding replay. The PNG Debug launch paused at its
breakpoint and resumed through completion. All 2,414 core, 57 CLI, and
879 plugin tests passed, together with the plugin coverage gates and
the instruction lab and PNG pixel checks.

An initial IDE profiling launch aborted before playback in the JetBrains
Runtime JFR sampler's native thread-suspension code. A fresh
`clean cleanSandbox runIde` replay completed with the same runtime and
profiling settings.

## Device checkpoint composition

Each registered device still captures its undo state before every instruction.
When a device returns the same callback and the preceding callbacks are
unchanged, Backstep now reuses their composition. It retains immutable callback
chains for older instruction frames. Tests exercise capture order, changing
callbacks, null callbacks, unsubscription, duplicate factories, reservation
restore order, and undo after the history wraps.

The core benchmark accepts an optional count of stable device callbacks and
a scalar PNG mode.

```sh
./mars5/gradlew -p mars5 :core:benchmarkExecution -PexecutionBenchmarkCheckpoints=3
./mars5/gradlew -p mars5 :core:benchmarkExecution   -PexecutionBenchmarkScalar=true -PexecutionBenchmarkObserver=data
```

Scalar runs assert 59,391,652 completed instructions and 8,530,372 data
accesses when observation is enabled. The MSA counts remain 53,035,658
instructions and 9,004,117 data accesses.

Paired core measurements with three stable callbacks were approximately
4.1 s before and after composition reuse. With no device callbacks the
medians were 3.906 s before and 3.928 s afterward. The principal benefit is
allocation reduction. The baseline core JFR attributed 10.46 GB of sampled
cumulative allocation to composed callbacks over its warmup and three measured
runs. Those callbacks disappeared from the leading allocation sites after
the change.

The subsequent clean IntelliJ replay measured 10.236, 9.839, and 9.583
seconds. Its median overlaps the previous replay's spread. Composition
callbacks also disappeared from the leading IDE allocation sites, where
the preceding replay had attributed 7.93 GB to them. These results establish
the allocation improvement while leaving execution-time improvement uncertain.

## Assembly lookup and token classification

The assembler now indexes instruction forms with the JDK's case-insensitive
string comparator. Lookups retain registration order, Unicode case matching,
and independent result lists. The numeric token classifier checks the first
character before attempting numeric conversion. Register lookup similarly
recognizes named spellings without raising numeric conversion errors.
Tests preserve Unicode digits, NaN and Infinity, hexadecimal floats, numeric
register aliases, and both instruction targets.

JMH assembled 10,000 instructions with two warmup iterations and three measured
iterations in each of two JVM forks on OpenJDK 25.0.1. Each iteration lasted
one second. Allocation figures come from JMH's GC profiler.

| Revision | Mean assembly time (ms) | Allocation (MB per assembly) |
| --- | ---: | ---: |
| Before lookup changes | 144.136 | 119.243 |
| Indexed instruction names | 70.473 | 118.602 |
| Numeric token shortcut added | 44.823 | 85.562 |
| Named register shortcut added | 7.716 | 34.682 |

The combined changes reduced this workload's assembly time by 95% and
allocation by 71%. Reproduce the final measurement from the repository root.

```sh
./mars5/gradlew -p mars5 :core:benchmark   --args='AssemblerBenchmark -wi 2 -i 3 -w 1s -r 1s -f 2 -prof gc'
```

A final JFR recording spread assembly execution samples across token storage,
source-map generation, path handling, and assembly passes. Exception-driven
instruction and register lookup had ceased to dominate.

## X-Ray history and final execution measurements

X-Ray now keeps its bounded event and memory buffers aligned by position.
Recording and evicting a completed step updates the two buffers together.
Display requests find the matching event within the retained history.
The tests verify memory details after wrap, rewind, re-execution, and clear,
including repeated program counters and value-equivalent event copies.

The final clean IntelliJ replay ran PNG in 9.124, 8.970, and 8.631 seconds,
giving a median of 8.970 seconds. That is 45% below the original 16.457 s
baseline and 7% below the 9.630 s observer replay. These latest measurements
include checkpoint reuse, assembly lookup changes, and X-Ray storage changes.
The PNG Debug launch paused at its breakpoint and resumed through completion.
The packaged replay also verified the ordinary Run, Debug, navigation, and
MARS64 FIR outputs. Its installed ZIP checksum was
`a50805ec9f070509683e5b278fbea9c43e2ce4fccd109f0797ff9c643eb8551c`.

Across the three PNG Run spans, X-Ray recording appeared in 133 of 2,026
simulator-thread samples, down from 234 of 2,219 in the preceding checkpoint
replay. Hash-based eviction and event hashing disappeared from the leading
execution sites. The composed checkpoint callbacks and X-Ray's map entries
also disappeared from the leading allocation sites.

The final pass verified 2,420 core tests and 57 CLI tests. The clean IDE
qualification passed 880 plugin tests and the plugin coverage gates.
The instruction lab executed 867 basic forms and 430 pseudo-instruction
forms, and all five PNG pixel smoke tests passed. After the IDE replay,
the checkpoint concurrency audit added serialization around duplicate-factory
removal and verified it with the full core suite and focused plugin tests.

## Stopping assessment

The remaining measured execution costs are distributed among instruction
semantics, register notifications, checkpoint capture, and immutable execution
events. Further changes to event representation, observer synchronization, or
provenance would broaden the concurrency and debugger lifecycle work. Assembly
is now under 8 ms for the measured 10,000-instruction workload, with its samples
spread across token storage, source maps, path handling, and assembly passes.
At this point those candidates offer diminishing returns relative to the
implementation and regression risk. The optimization pass stops here.

The retained checkpoint cache is chiefly an allocation improvement. Its paired
core execution times overlap the baseline. The larger measured gains came from
sharing immutable session setup, eliminating repeated lookup and failed parsing,
and reducing work done for each instruction's tool observations.
