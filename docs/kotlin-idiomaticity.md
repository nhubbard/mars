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
3. Dagger builder receiver DSL, retaining Dagger's validation.
4. Native listener implementations with explicit lifetimes and callback DSLs.
5. Completion providers and table column builders.
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
  an unmodifiable collection. Preserve both traversal and collection contracts.
- `runWriteAction` is the platform Kotlin wrapper for the same synchronous write
  action. These migrations do not introduce dispatching, suspension, or retries.
