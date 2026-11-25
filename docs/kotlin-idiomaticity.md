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
7. Core collection and simulation configuration APIs, preserving compatibility.
8. Nullable state and lifecycle invariants, followed by complete validation.

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
