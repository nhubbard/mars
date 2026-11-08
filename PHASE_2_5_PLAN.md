# Phase 2.5 — clean legacy baseline

Status: completed on 2026-11-08. The subsequent scope expansion eliminated the compatibility
runtime entirely and imported the replacement Kotlin scaffold as the final integration step.
The original audit and interim checkpoint below are historical; their warnings and compatibility
exceptions no longer describe the current tree.

## Completion record

- Removed the legacy module and its global/static facades, duplicate execution loop, global I/O,
  and Dagger bridge. Supported callers now use explicit session-owned APIs.
- Migrated Venus windows, editors, register views, settings, tools, and syscall I/O to independent
  desktop sessions. Tool providers receive their session; no global UI singleton is required.
- Moved useful characterization tests into core. Removed tests whose sole contract was the retired
  compatibility lifecycle; retained behavior coverage for assembly, execution, hardware, undo,
  settings, events, I/O, and multiple sessions.
- Replaced deprecated settings helpers and JDK desktop APIs. Java production and test compilation
  rejects deprecation/removal warnings; Kotlin compilation treats warnings as errors.
- Replaced thread-name inference with lexically scoped execution origins for tool notifications.
- Split `desktop` from `plugin`. The Kotlin plugin depends only on core and owns a lazy session per
  IntelliJ project, disposing it with that project. No Venus classes are packaged in the plugin.
- Imported the supplied Kotlin 2.4.21 / JDK 25 scaffold into `mars5/plugin`; reused the repository's
  Gradle 9.9 wrapper and kept the original scaffold metadata in a temporary backup.

Acceptance evidence: the full build and plugin packaging pass, with 1,201 tests (core 1,183,
CLI 2, desktop 14, plugin 2). Packaged CLI/desktop launchers execute Hello World, and a native
smoke check creates two windows with independent registers and editor state. Detailed visual
interaction, every tool, and IntelliJ language/debugger behavior remain outside these checks.

## Plugin language decision and next milestone

The independent engine and CLI remain Java for now. All new IntelliJ plugin implementation and
tests are Kotlin. The previously proposed Kotlin engine migration is deferred by the subsequent
language decision. It is not a dependency of plugin integration.

Follow [the detailed component mapping](INTELLIJ_COMPONENT_MAPPING.md) for complete MARS feature
parity inside IntelliJ 2026.3, near-full meaningful branch coverage, and Jupiter-only tests.
The mapping inventories all production source files and resources, including all educational tools.
No existing user feature is deferred there. The desktop remains a working transition frontend.

## Original audit plan (historical)

## Acceptance criteria

- Compile core, CLI, legacy, and frontend production and test sources on Java 25 with explicit
  deprecation/removal diagnostics; no deprecated API calls remain in application code.
- Replace deprecated APIs rather than deleting their annotations or adding blanket suppressions.
  Retained public compatibility entry points remain explicitly deprecated; any deliberate tests of
  those entry points must be narrowly identified as compatibility-contract tests.
- Keep the independent core free of desktop and compatibility dependencies.
- Preserve the existing editing, input, shortcut, display, assembly, simulation, undo, and tool
  notification behavior through the replacements.
- Run the complete characterization/core suite and packaged CLI/frontend smoke checks.
- Enforce the clean baseline in Gradle so future deprecated calls fail compilation.

## Work order

1. **Establish the warning inventory.** Separate calls to deprecated MARS facades/settings helpers
   from JDK desktop, input, text-rendering, and observer APIs. Preserve the working-tree cleanup
   already in progress. Audit fresh compiler output after that cleanup settles.
2. **Migrate application call sites to session APIs.** Replace register/coprocessor facade access
   with the canonical session's hardware and instruction context. Keep undo-aware writes on the
   instruction context. Separate legacy frontend composition/lifecycle from deprecated external
   compatibility entry points rather than encouraging new calls to the facade.
3. **Use supported settings accessors.** Replace deprecated per-setting getters/setters with the
   indexed settings API while preserving persistence and notification behavior.
4. **Finish typed tool notifications.** Remove obsolete observer parameters and ensure callbacks
   actually override and receive the typed memory/register notifications. Add regression coverage
   for the notification routes affected by the migration.
5. **Replace obsolete desktop APIs.** Update shortcut masks and input-event handling together;
   migrate text-coordinate, tab measurement/drawing, font metrics, popup, and focus APIs without
   changing selection, rendering, or keyboard behavior.
6. **Address warnings exposed by strict compilation.** Fix relevant raw/unchecked usages that
   prevent a clean legacy/frontend baseline. Keep public compatibility behavior and binary/source
   entry points where they are still intentionally supported.
7. **Validate and enforce.** Run warning-clean compilation, the full test suite, jar smoke tests,
   and focused headless tests for keyboard/mouse modifiers, text rendering/measurement, settings,
   and typed tool notifications. Record remaining interactive desktop QA explicitly.

## Initial audit

The first production-source audit reported 608 deprecation diagnostics across 69 files. The largest
categories were `Globals` (368), integer-register facade calls (61), Coprocessor0 (41), and
Coprocessor1 (32). Other categories included settings convenience methods, obsolete observer types,
shortcut masks, input modifiers, text utilities, font metrics, focus, and popup APIs.

These counts describe the initial snapshot, not a fixed scope limit. Existing working-tree edits
were already updating some of these sites while the audit ran; the final inventory must come from
fresh compiler output. Deprecation annotations on compatibility declarations are not themselves
uses of deprecated APIs and should not be removed merely to make a build quiet.

## Phase boundary

Phase 3 begins only after the clean legacy baseline is validated. No IntelliJ Platform SDK setup,
PSI/language support, new tool windows, or replacement UI belongs to Phase 2.5.

## First cleanup checkpoint — 2026-11-08 (historical)

The current checkpoint prioritizes non-Dagger production uses of deprecated static globals.
It does not complete the settings/JDK cleanup or enable blanket deprecation-as-error enforcement.

- Reviewed the existing inspection edits. Kept equivalent brace, import, record, switch, string,
  and arithmetic simplifications. Restored the public `SymbolTable` constructor signatures while
  retaining removal of the unused filename field, and repaired a missing hardware-exception import.
- Restored BitmapDisplay, CacheSimulator, and MemoryReferenceVisualization notification handlers
  and their necessary helpers. Converted them to the typed override that the base class calls.
  DigitalLabSim and FloatRepresentation now override the shared typed notification hook; the
  latter continues receiving user-originated writes as well as simulated writes.
- Corrected RepeatButton's use of extended mouse-event modifiers: generated ActionEvents retain
  the documented ActionEvent keyboard flags. The mouse button itself is not a keyboard modifier.
- Added a scoped legacy Dagger component and compatibility bindings. The existing frontend uses
  its canonical session, hardware, instruction context, settings, and live frontend state through
  `LegacyServices`. `LegacyApplication` remains the composition entry point for this single
  existing desktop application and standalone tools. This is a transitional bridge, not a new
  independent session or the future IntelliJ project-service implementation.
- Moved resource/configuration metadata into `LegacyMetadata`; compatibility metadata collections
  remain shared. Bound the memory facade during bootstrap to avoid recursive component creation.
- Preserved undo-aware hardware writes and register reset's start-at-main behavior. Deprecated
  external entry points remain available and deprecated. No blanket warning suppression was added.
- Added `verifyLegacyFacadeBoundary` to the root build check. It rejects direct production access
  to Globals/RegisterFile/Coprocessor0/Coprocessor1 outside the compatibility declaration and the
  single Dagger binding module.

Validation: the full Gradle build, core isolation check, and benchmark-source compilation pass.
All **1,189 tests** pass (1,036 core, 149 legacy, 2 CLI, 2 frontend). New regression checks cover
shared compatibility/Dagger hardware, live program/exit state, undo, independent modern sessions,
start-at-main reset, and repeated-button modifiers. The packaged standalone CLI and packaged
legacy launcher both print Hello World in fresh headless JVMs. Interactive desktop tool rendering
and input still require manual QA; compilation and headless tests do not establish visual behavior.

A fresh explicit deprecation/removal audit reports **78 production warnings in 31 files**:

| Remaining category | Diagnostics | Follow-up |
| --- | ---: | --- |
| Globals in `legacy/di/LegacyBindings.java` | 16 | Deliberate Dagger compatibility boundary; retire with the old external lifecycle |
| Deprecated Settings convenience accessors | 54 | Migrate to indexed settings access while preserving notifications and persistence |
| JDK font metrics, tab measurement/drawing, focus APIs | 8 | Replace with behavior-preserving desktop APIs and focused rendering/input checks |

There are **zero production diagnostics for the deprecated global classes outside Dagger**.
The audit also reports 265 test-source diagnostics across 15 legacy test classes. These exercises
compare or characterize the deprecated external compatibility API and shared legacy runtime;
identify their contract scope explicitly before enabling strict test-source deprecation checks.
Annotations remain present, and warnings remain visible.

The next Phase 2.5 step is the settings-accessor migration, followed by the remaining desktop APIs
and narrowly scoped compatibility-test policy. Phase 3 remains gated on the full acceptance criteria.
