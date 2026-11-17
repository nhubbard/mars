# MARS session startup

Run and Debug create a new execution session through the existing IntelliJ
factory. Each target shares its instruction definitions, operand examples,
pseudo-instruction templates, and binary decoding tables across sessions.
The editor also uses these definitions. Its cross-target catalog recognizes
legacy source forms while decoding with the Release 6 tables. A target change
selects a catalog for the current session and updates its memory decoders.

IntelliJ's build cache continues to use source revisions and assembly options
to key its images. On a cache hit, launch reconstructs statements and memory
in a fresh session. The catalog indexes the exact instruction form stored in
each image so the loader can look it up directly while preserving form order.

## Core startup benchmark

From the repository root, run this command with JDK 25. Run it multiple times
for independent fresh JVM samples.

```sh
./mars5/gradlew -p mars5 :core:benchmarkSessionStartup
```

The benchmark times the first core session construction in a new JVM. It then
assembles the MARS64 PNG decoder outside the timed phases and measures the first
load of that image and the time from load completion to the first simulator step
callback. It runs 30 warmup launches and 80 measured launches in the same JVM,
each using a new core session and the same image. The printed total sums the three
timed phases for each sample. The cold total combines first construction with
the first image load and execution after assembly. All timings exclude assembly,
session disposal, and IntelliJ work.

Results on arm64 macOS with OpenJDK 25.0.1. The warm columns report the median
of the per-JVM percentiles across three JVM runs per revision. The cold columns
report the median across three first launches.

| Phase | Before warm p50 / p95 (ms) | After warm p50 / p95 (ms) | Before cold (ms) | After cold (ms) |
| --- | ---: | ---: | ---: | ---: |
| Construction | 21.694 / 24.272 | 0.581 / 0.672 | 176.653 | 177.894 |
| Cached image load | 5.164 / 5.488 | 0.115 / 0.186 | 5.381 | 1.167 |
| First executed instruction | 0.143 / 0.180 | 0.072 / 0.124 | 5.667 | 5.041 |
| Total of timed phases | 27.034 / 29.849 | 0.775 / 0.904 | 187.940 | 184.371 |

The baseline was measured at the benchmark commit `eb08e723` before the
optimization, and the final core measurements at `3a7caebf`. First construction
ranged from 171 to 232 ms before the change and 176 to 179 ms afterward.

## Startup profile

To record the same workload, supply an absolute destination for JFR.

```sh
./mars5/gradlew -p mars5 :core:benchmarkSessionStartup \
  -PsessionStartupRecording=/tmp/mars-session-startup.jfr
```

The recording made after catalog sharing and before the image lookup change
showed catalog initialization and instruction class loading on first construction.
During repeated loads, 39 of 47 execution samples included
`ProgramStatement.Image.load` and the catalog's mnemonic scan. The direct lookup
removed this scan and brought median warm load time to 0.115 ms.

## IntelliJ qualification

From `mars5`, build and run the packaged plugin in a clean sandbox.

```sh
./gradlew clean cleanSandbox runIde -PpackagedSmoke
```

The IntelliJ 2026.3 replay ran the short MARS64 fixture three times and
debugged it three times. Each Debug launch paused at the requested source
breakpoint. The first Debug launch also stepped into the included routine.
The replay verified both output files, opened MARS Tools, and verified the
MARS64 FIR result. All 877 plugin tests and the plugin coverage checks passed.

| IntelliJ playback span (ms) | First | Second | Third |
| --- | ---: | ---: | ---: |
| Run, process starting to exit | 120.1 | 3.9 | 3.2 |
| Debug, launch request to first breakpoint | 224.9 | 91.3 | 83.6 |

The Run span includes execution of the short fixture and starts at IntelliJ's
`processStarting` event. The Debug span begins when playback requests launch
and ends when the debugger pauses. These are post-change measurements from
one replay. The sandbox log directory contains `packaged-smoke-trace.json`
and `packaged-smoke-SHA256SUMS`.

Background prewarming is deferred. Repeat launches spent under a millisecond
in the measured core phases, and opening MIPS source can already initialize
the catalog that launch uses. The cold core benchmark still shows the one-time
initialization cost. An IntelliJ measurement that isolates catalog initialization
on a cold launch would establish whether shifting that work earlier helps.
