# Kotlin idiomaticity refactoring

This work preserves simulator behavior and IntelliJ's threading, PSI, disposal,
and extension contracts. The reference checkout is IntelliJ 2026.3 and is read-only.

## Repeatable audit

Run `python3 tools/audit-kotlin-idiomaticity.py` from any directory. The inventory
covers tracked and unignored Kotlin and Gradle Kotlin files, excluding generated
build output through Git's normal ignore rules. These are textual counts, including
occurrences in comments and strings; they are an inventory, not a quality score.
The baseline is recorded in `kotlin-idiomaticity-before.json`. Raw DynamicBundle
counts include the existing helper's implementation.

## Phases and evidence

1. Native Kotlin APIs: Jupiter assertions, configuration types, services, write
   actions, and semantically equivalent PSI operations. Complete. All 2,446 core,
   317 CLI, 8 datapath, and 1,364 plugin tests passed; benchmark sources compile,
   and strict plugin/generated-parser coverage gates pass. Test class tokens
   decreased from 943 to 467.
2. Narrow reified interoperability helpers, subsystem organization, and bundles.
   Complete: 2,450 core, 317 CLI, 8 datapath, and 83 selected plugin tests pass;
   all affected modules and benchmark sources compile. Every new production
   helper line is covered through a real consumer. Test-only adapters stay in
   test sources. All bundle construction uses the existing helper. The audit
   now finds zero test class literals and four elsewhere (three in the two
   class-token helpers and one in the independent grammar generator).
3. Dagger builder receiver DSL, retaining Dagger's validation. Complete:
   migrated all 77 construction chains, leaving only the generated-builder call
   in the helper. All 2,454 core, 317 CLI, 8 datapath, and 10 selected plugin
   tests pass. Standalone instruction-lab and PNG smoke checks pass, and benchmark
   sources compile. New tests cover default modules, missing bindings, configuration
   exceptions, exact binding identity, and independent execution state.
4. Native listener implementations with explicit lifetimes and callback DSLs.
   Complete: migrated 66 anonymous listener objects. All 2,457 core, 317 CLI,
   8 datapath, and 1,373 plugin tests pass, including the four new platform
   contract tests. Strict plugin and generated-parser coverage gates pass.
5. Completion providers and table column builders. Complete: native completion
   providers and columns replace the repeated objects. The focused completion,
   debugger, and new column-contract tests pass; all affected modules compile.
6. Remaining callback adapters, retaining explicit objects where clearer.
   Complete: native focus and lazy PSI APIs, thin debugger outcome/starter
   and table callback adapters. All 134 selected debugger, learning-anchor,
   memory-display, and new callback-contract tests pass; affected modules compile.
7. Core collection and simulation configuration APIs, preserving compatibility.
   All 2,461 core, 317 CLI, and 8 datapath tests pass; benchmark sources compile.
   All 186 selected plugin token/settings, completion, parser, and Run tests pass.
8. Nullable state and lifecycle invariants. Complete: the full 2,463 core,
   317 CLI, and 8 datapath suites and 122 selected Debug tests pass. Final
   lifecycle/image and debugger-error regressions also pass. Benchmark sources
   compile. Production non-null assertions decrease from 263 to 219; see
   `kotlin-nullability-audit.md` and its repeatable remaining-assertion inventory.

Complete repository and packaged-plugin validation follows these phase checks.

Every phase is reviewed, compiled, tested, and committed before the next begins.

## API decisions

- `childOfType` performs recursive search; direct-child Java searches use
  `childrenOfType().firstOrNull()` instead. Recursive `findChildOfType` can use
  `childOfType` directly. Neither substitution includes the receiver itself.
- `descendantsOfType` includes its root and returns a lazy sequence. It cannot
  mechanically replace `findChildrenOfType`, which excludes the root and returns
  a collection annotated unmodifiable by the platform. Preserve both traversal and collection contracts.
- `runWriteAction` is the platform Kotlin wrapper for the same synchronous write
  action. These migrations do not introduce dispatching, suspension, or retries.

- Reified `T::class.java` boxes primitive type arguments. `javaClassOf<T>()`
  explicitly recovers the primitive token where available; `boxedClassOf<T>()`
  retains the object token. Object-instance filtering and AssertJ adapters use
  boxed tokens. Tests cover primitive/reference distinctions and array components.
- PSI pattern captures retain the nullable generic type expected by the Java
  rename-validator contract; matching still rejects objects outside that type.
- Typed Swing, PSI, event, producer, and debugger helpers delegate to native APIs.
  Traverser filtering returns the native lazy `JBIterable`, not an eager list.
- The standalone grammar generator deliberately retains its class literal: its
  isolated classpath does not include MARS runtime helpers. Adding a runtime
  dependency solely to remove that literal would weaken the build boundary.
- Test-only XML deserialization uses the existing platform Kotlin function.
  AssertJ extensions remain test-only and return the original assertion objects.

- Listener configuration snapshots callbacks when construction finishes. Callback
  receivers are the actual native listener, including for self-unregistration.
  Factories add no scheduling, exception handling, event buffering, or lifetime
  registry. Existing registration/disposal stays with the simulator or platform.
- `ExecutionListener.processNotStarted` with a cause retains native forwarding
  to its two-argument overload when no cause callback is supplied. Unconfigured
  terminating/terminated callbacks also retain their native default forwarding.
- Debug-session boolean frame notifications retain the native forwarding to the
  no-argument callback. Internal frontend callbacks are neither overridden nor
  explicitly invoked by the factories.
- Document callbacks remain plain per-change listeners, including during bulk
  updates. Converting them to bulk-coalesced listeners would change timing. The
  trailing-lambda registration helper requires the original parent disposable
  and returns the exact instance registered with the platform.
- Stateful named listeners, including debugger call-stack state and process
  recording fixtures, remain explicit classes. Their state and lifecycle are not
  hoisted into configuration blocks.

- The retained-debug-callback fixture snapshots native registrations before and
  after the checkpoint subscribes, filters the MARS namespace, and keeps actual
  listener identities. SDK modules may add listeners concurrently and can share
  the fixture's classloader. No generated class-name assumption or production
  capture hook is needed; the original stale/disposed-callback checks remain.

- Completion registration delegates directly to `CompletionContributor.extend`.
  It preserves nullable completion types, native patterns, processing contexts,
  results, and synchronous callback propagation.
- Columns retain native nullable values, String class defaults, read-only cells,
  and renderer/editor behavior. Only comparators and column classes need builder
  options today. `buildColumnInfo` has a distinct name from the value-only factory
  to avoid ambiguous Kotlin Function1 overloads. Builder settings are snapshotted.

- The native `whenFocusGained` helper retains component lifetime for the memory
  grid. Its mouse-click handler remains explicit: it is a short single local
  handler with no repeated callback boilerplate. The disassembly header adapter
  retains its before/after layout state and two-event pairing.
- Renderer factories override only customization, retaining native clearing,
  selection/focus setup, and cell state. Debug modification callbacks require both
  outcomes and propagate exceptions without scheduling or catching them.
- The script-debugger module has a similar starter helper, but MARS does not
  depend on that separate module. The local three-line native adapter avoids
  adding an unrelated debugger dependency. Sessions remain uninitialized during
  the factory callback; initialization still belongs in `sessionInitialized`.
- Native lazy pre-order PSI traversal replaces local visitors only where root
  inclusion and short-circuiting are equivalent. Inspection visitors and index
  walkers with distinct contracts remain native visitors.
- Remaining objects include native instruction subclasses, breakpoint handlers,
  debugger layouts/presentations, refactoring and undo actions, persistent UI
  subclasses, test doubles, and the small implementations inside these factories.
  Their state, inheritance, or multi-method contracts remain explicit.

- `SimulationSettings` now exposes mutable Kotlin properties without changing
  `MarsSettings`' read-only interface or removing fluent setters. Explicit setter
  JVM names avoid colliding with fluent methods returning `SimulationSettings`.
  The properties delegate to those original setters, including preference-owner
  routing. `configureSimulation` applies immediately in source order, returns the
  same settings object, and does not batch notifications or roll back exceptions.
  Boolean names retain the existing `is...` API rather than creating aliases.
- `TokenList.size` delegates to the original virtual `size()` for subclass and
  JVM compatibility. `indices` and `Iterable<Token>` support native Kotlin loops;
  the iterator comes from a truly unmodifiable Java view, so a Java/MutableIterator
  cast cannot bypass freeze guards. Token values retain their own freezing, and
  cloning frozen lists continues to produce independent mutable example tokens.
  No mutable collection interface or backing-list accessor has been exposed.

## Final audit

| Metric | Before | After |
| --- | ---: | ---: |
| Kotlin / Gradle Kotlin files | 970 | 1,002 |
| Java class literals in tests | 943 | 0 |
| Java class literals outside tests | 51 | 4 |
| Anonymous objects in tests | 139 | 92 |
| Anonymous objects outside tests | 103 | 76 |
| Raw Dagger builder calls | 77 | 1 |
| Raw DynamicBundle constructors | 24 | 1 |
| Production non-null assertions | 263 | 219 |

The after snapshot is `kotlin-idiomaticity-after.json`. Both snapshots use the
same tracked/unignored source inventory and textual counting rules. Remaining
raw Dagger and bundle calls are their factories' implementations. The four class
literals are centralized interop implementations and the isolated grammar
generator; no Java class literals remain in tests. Required Kotlin class literals
in annotation metadata are unchanged. Anonymous objects remain where they
implement a native factory, express stateful behavior, or preserve a clearer
inheritance/extension contract. New regression doubles also contribute to that
count.

## Coverage follow-up

The first complete post-refactoring run passed all 1,378 plugin tests. Strict
branch verification identified two gaps, which were addressed without relaxing
thresholds or adding exclusions. The macro inspection now iterates token/index
pairs directly with `withIndex()` instead of constructing a range and looking up
each token. Its native PSI statements are nonempty; the former custom-range
loop had introduced an unreachable empty-range branch at that call site.

The debugger execution binding now validates its loaded-program invariant in its
constructor. A direct regression test covers both an unloaded session and a
loaded binding that must retain its original program across reassembly. All 36
focused binding and macro-inspection tests pass, with 100% line and branch coverage
in both corrected source files. The packaged verifier reports compatibility with
IntelliJ IU-263.7307.200, with 97 experimental API usages.

A full run also exposed a read-only include identity issue. Native PSI CachedValues
use soft references and can recreate a source projection while an editor retains
its previous PSI. That breaks include membership keyed by the projection's
VirtualFile. A binary physical file also cannot be registered as a text copy via
`originalFile`: native VFS refresh would attempt to load binary contents as text.

The existing project service now weakly indexes read-only projections by physical
file, display name, original file type, and decoded text. The live PSI retains its projection entry;
closed projections can be collected. Only the published entry creates a VirtualFile,
using lazy initialization to retain native concurrent-provider semantics. Parsing,
dependency invalidation, text decoding, and refresh remain with the existing native
PSI/CachedValue and disk-capture paths. No alternate PSI or event framework is added.

The regression forces native cache collection while retaining the visible syntax,
verifies projection identity and the missing-file fallback, and keeps binary copies
independent from unsupported text-copy registration. All 61 selected capture,
expansion, macro-inspection, and macro-reference tests pass. New projection paths
have complete line and branch coverage in the focused report.

The projection key also preserves file-type reclassification independently of
text changes. The full annotator regression class is included in the focused
checks, covering original-name changes, binary/text reassignment, live document
edits, and VFS refresh in addition to native cache collection.

## Complete verification

The final source passes the complete build on JDK 25 against IntelliJ
IU-263.7307.200. All 2,463 core, 317 CLI, 8 datapath, and 1,380 plugin tests pass,
with zero failures, errors, or skipped tests. The plugin package coverage gates
retain 100% line and branch thresholds, and the separate generated-parser gate
passes. Formatting, benchmark compilation, plugin structure, project
configuration, license notices, and packaged verification pass.

The packaged verifier reports `Compatible`, with 97 experimental API usages and
no configured compatibility, internal-API, or override-only API violations.
The final command is:

```sh
./mars5/gradlew -p mars5 build :core:benchmarkClasses \
  :plugin:verifyPluginStructure :plugin:verifyPluginProjectConfiguration \
  :plugin:verifyPlugin
```

Standalone smoke verification passes the instruction-lab test and all five PNG
decoder tests. The catalog exercise executes 867 basic and 430 pseudo-instruction
forms, including their expected traps, with an empty failure report. The CLI JAR
also passes its `--help` smoke check with JDK 25. The final focused source-view
contract run passes all 91 tests before the complete suite.

The final audit matches the committed after snapshot. The remaining assertions
and deferred type/lifecycle improvements are documented in
`kotlin-nullability-audit.md`; this work does not claim that every nullable public
compatibility API has been redesigned.
