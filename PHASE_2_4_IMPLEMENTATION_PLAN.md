# Phase 2.4 Implementation Plan — Dependency Injection for the Simulator

> Historical implementation plan for commit `cba254a`. This work and the later compatibility-runtime
> removal are complete; use [the Phase 2.5 completion record](PHASE_2_5_PLAN.md) for the current state.


**Scope:** Complete MODERNIZATION_PLAN.md step 2.4 ("Replace the `Globals` singleton with
constructor-injected dependencies"). This document is an implementation-ready breakdown for
whoever (human or agent) picks up the work. It reflects the actual state of the codebase as of
commit `cba254a` (task: Finish phase 2.0 by updating to use injected values), not just the
illustrative sketch in MODERNIZATION_PLAN.md §2.4.

## 0. Current state (read this before writing any code)

Step 2.4 is partially scaffolded but the substantive work has not started. Concretely:

- `edu.missouristate.mars.core.MarsContext` exists (`mars5/core/src/main/java/edu/missouristate/mars/core/MarsContext.java`)
  but only holds `settings` (`SimulationSettings`) and `gui` (`MarsGuiProxy`). Its own javadoc
  contains a literal TODO: *"add Memory, RegisterFile, InstructionSet, Simulator as constructor
  params once their implementations live in mars-core."*
- Dagger DI scaffolding already exists and should be reused, not replaced:
  `edu.missouristate.mars.di.MarsComponent` (root `@Component`), `edu.missouristate.mars.di.CoreModule`
  (provides `SimulationSettings`, binds `MarsSettings`), and
  `edu.missouristate.mars.plugin.di.PluginComponent` / `PluginModule` (adds `VenusMarsGuiProxy` as
  the plugin's `MarsGuiProxy` binding). MODERNIZATION_PLAN.md's code sketch (a plain
  `new MarsContext(settings)` constructor) predates this and should be treated as illustrative
  intent, not a literal spec — follow the Dagger convention already committed to.
- `Globals` (`mars5/core/src/main/java/edu/missouristate/mars/Globals.java`) is **completely
  unmigrated** — still the original static-field singleton from the pre-modernization codebase.
  147 call sites reference `Globals.*` across `mars5/core` (69 files) and `mars5/plugin` (58 files).
  There is no `mars5/venus` module — the Venus Swing GUI lives inside
  `mars5/plugin/src/main/java/edu/missouristate/mars/plugin/venus/` as part of the IntelliJ plugin.
- **`Memory`** (`mars5/core/.../mips/hardware/Memory.java`) is an eager singleton: private
  constructor, `private static final Memory uniqueMemoryInstance = new Memory()`, accessed via
  `Memory.getInstance()`. It also declares ~20 `public static int` fields (`textBaseAddress`,
  `dataSegmentBaseAddress`, `stackBaseAddress`, `kernelBaseAddress`, ... — lines 53–224) that hold
  the *active* memory configuration and are mutated in-place by the static method
  `setConfiguration()`. These fields are global mutable state **independent of the singleton
  instance** — even after `Memory` itself is made instantiable, two `Memory` instances would still
  share (and stomp on) the same address configuration unless these fields are also moved to
  instance state. `Globals.memory` is referenced 66 times.
- **`RegisterFile`, `Coprocessor0`, `Coprocessor1`** (`mars5/core/.../mips/hardware/`) are **not**
  singletons — they are 100% static utility classes. Every field (the `Register[]` arrays,
  `EventEmitter` instances) and every method (`getValue`, `updateRegister`, `getRegisters`,
  `setRegisterToFloat`, etc.) is `static`. There are no constructors and no instance state at all.
  These classes are called directly, **bypassing `Globals` entirely today**:
  - `RegisterFile.*` — 160 call sites
  - `Coprocessor1.*` — 94 call sites
  - `Coprocessor0.*` — 17 call sites
  - 134 files under `mars5/core/.../mips/instructions/` (the ~178 instruction implementations)
    reference `RegisterFile` directly.

  **This is the largest and highest-risk part of step 2.4.** Nothing in MODERNIZATION_PLAN.md's
  sketch (`this.registerFile = new RegisterFile();`) accounts for the fact that `RegisterFile` has
  no instance API to construct in the first place — the class must be redesigned from a static
  utility into an instantiable type before it can be "constructor-injected."
- **`InstructionSet`** is already instance-based (`public InstructionSet()`, instance method
  `populate()`) and stored as an instance in `Globals.instructionSet`. Only 6 direct external
  references outside of `Globals.instructionSet` — this one is comparatively easy.
- **`Simulator`** (`mars5/core/.../simulator/Simulator.java`) is a singleton
  (`private static Simulator simulator`, `getInstance()`), plus `static volatile` fields
  (`externalInterruptingDevice`, `observer`). Its inner `SimThread` still extends the pre-Java-6
  `SwingWorker` — that conversion is covered separately by MODERNIZATION_PLAN.md §2.8 and is
  **out of scope** for this plan, but `Simulator`'s constructor-injection work (§2.4) and its
  threading rewrite (§2.8) touch the same file, so sequence them deliberately (see §6 below).
- **`Assembler`** and **`BackStepper`** have no constructor parameters at all today. They reach
  into `Globals.memory`, `Globals.symbolTable`, `Globals.debug`, `Globals.instructionSet`,
  `Globals.getSettings()`, `Globals.maximumBacksteps`, plus direct static calls to `RegisterFile`,
  `Coprocessor0`, `Coprocessor1`.
- **Settings duality**: `Globals.settings` is typed `Settings` (the legacy, GUI-era class with
  `EventEmitter<Settings>`-based change notification, presumably backed by `Preferences`/registry
  storage — not yet inspected in depth here). The new `SimulationSettings` implements the headless
  `MarsSettings` interface and currently has **hardcoded defaults** (see its own javadoc: *"Phase 1
  stub... In a later phase it will be backed by a Properties file and the legacy Settings class
  will delegate to it"*). `Settings` does **not** implement `MarsSettings` today. `MarsContext.settings`
  and `Globals.settings` are therefore two unrelated objects that can silently disagree (e.g. a user
  changing "delayed branching" in the GUI's `Settings` would have zero effect on a `MarsContext`-driven
  simulation). This must be reconciled as part of 2.4, not deferred — otherwise the migration produces
  two settings stores that drift.
- **Entry points that call `Globals.initialize()`** (the thing that must eventually be replaced by
  "get a `MarsContext` from a Dagger component"):
  - `mars5/plugin/.../plugin/MarsLaunch.java:135` — batch/command-line launch path
  - `mars5/plugin/.../plugin/tools/AbstractMarsToolAndApplication.java:153` — base class for all MARS tools
  - `mars5/plugin/.../plugin/venus/VenusUI.java:168` — main GUI window
  - 4 test classes: `AssemblerSmokeTest`, `InstructionSetTest`, `SimulatorSmokeTest`, `MemoryTest`
    (`mars5/core/src/test/java/edu/missouristate/mars/`)
  - No test anywhere currently constructs `MarsContext`/`MarsComponent` directly — the DI
    scaffolding added in "The Great Divide" (commit `6a677d6`) is presently dead code, exercised by
    nothing.

## 1. Guiding constraints

1. **No flag day.** 147+ `Globals.*` call sites and 271+ direct `RegisterFile`/`Coprocessor0`/
   `Coprocessor1` call sites cannot be migrated atomically without an enormous, unreviewable diff.
   Follow the plan's own instruction: *"Do this class by class. The deprecated `Globals` facade
   still compiles and delegates to a process-global `MarsContext` instance so existing callers... don't
   break."* Apply the same bridging principle to `RegisterFile`/`Coprocessor0`/`Coprocessor1`: convert
   them to instance classes, then keep temporary `@Deprecated` static delegating methods that forward
   to a process-global instance, so unmigrated call sites keep compiling during the transition.
2. **Preserve the Dagger convention already in the repo.** Don't introduce a second, competing DI
   mechanism. `MarsComponent` / `CoreModule` / `PluginComponent` / `PluginModule` are the intended
   composition root; extend them rather than hand-rolling `new MarsContext(...)` call sites.
3. **The acceptance test is the point of the whole exercise.** MODERNIZATION_PLAN.md's stated goal
   is "you can instantiate two independent `MarsContext` objects in the same JVM." Every sub-step
   below should be validated against that, not just "does it compile."
4. **Don't couple this to §2.8 (Simulator threading rewrite).** `Simulator` needs surgery for both
   steps; do the constructor-injection change first (smaller, mechanical) and land it before
   touching the `SwingWorker`/`SimThread` internals, so the two refactors can be reviewed and
   reverted independently.

## 2. Sequencing (do these in order — each depends on the previous)

### 2.4.1 — Reconcile `Settings` vs `SimulationSettings`

Before touching any hardware class, resolve the settings duality, since almost everything else
reads settings somewhere.

- Decide the direction: either (a) make legacy `Settings` delegate to a `SimulationSettings`
  instance for the fields `MarsSettings` covers (memory configuration, delayed branching,
  self-modifying code, warnings-as-errors, extended assembler, assembler error limit), keeping
  `Settings`'s GUI-only fields (fonts, colors, window geometry, persistence) as-is; or (b) make
  `Settings implements MarsSettings` directly and retire `SimulationSettings` as a separate class.
  (a) matches the plan's own stated direction in `SimulationSettings`'s javadoc and keeps the
  headless/GUI split clean — prefer it.
- `CoreModule.provideSimulationSettings()` currently does `new SimulationSettings()`
  unconditionally. Once `Settings` can produce/wrap a `SimulationSettings`, decide whether
  `MarsContext` should own the canonical settings object (GUI reads from it) or `Settings` should
  (headless reads from it) — pick one canonical owner to avoid drift. Given `Globals.settings` is
  the older, more deeply embedded object (53 files call `Globals.getSettings()`), the pragmatic
  choice is: **`Settings` stays canonical during the migration window; `SimulationSettings` becomes
  a live view/adapter over it**, not a second store with its own defaults.

### 2.4.2 — Convert `RegisterFile`, `Coprocessor0`, `Coprocessor1` from static utilities to instance classes

This is the largest chunk of work. For each of the three classes:

1. Move every `static` field (register arrays, `EventEmitter` instances, condition-flag state in
   `Coprocessor1`) to instance fields. Add a public constructor that initializes them (this is
   exactly what today's `static { ... }` initializer blocks already do — move that logic into the
   constructor).
2. Convert every `static` method to an instance method (signatures otherwise unchanged).
3. Keep the small number of genuine constants (`GLOBAL_POINTER_REGISTER`, `STACK_POINTER_REGISTER`,
   `Coprocessor0.VADDR`/`STATUS`/`CAUSE`/`EPC`, `DEFAULT_STATUS_VALUE`, etc.) as `static final` — they
   are true constants, not shared mutable state.
4. Add a temporary compatibility shim so the 271+ existing call sites keep compiling without being
   touched in this same change: give each class a `@Deprecated static` facade that mirrors the old
   API and delegates to a process-global instance (owned by, e.g., a new package-private holder or
   by `Globals` itself — see 2.4.5). Mark every shim method `@Deprecated` with a javadoc pointing at
   the instance API, so `-Xlint` output from step 2.2 gives a concrete migration checklist.
5. Do **not** attempt to migrate the 134 instruction-implementation call sites in this same step —
   that's 2.4.3.

Validate after this step: `Coprocessor0Test`/`Coprocessor1Test`/`RegisterFileTest` if they exist,
or new unit tests, should be able to construct two instances of each class and confirm mutations on
one don't affect the other.

### 2.4.3 — Migrate instruction implementations off the static shims

- Every class under `mars5/core/.../mips/instructions/` (basic + syscalls + the ~178 instruction
  impls) needs to receive its `RegisterFile`/`Coprocessor0`/`Coprocessor1` instance from whatever
  invokes it, rather than calling the static shim. Look at how instructions are currently invoked
  (likely a `SimulationCode.simulate(...)` style interface) — the cleanest injection point is
  probably to pass the owning `MarsContext` (or the three hardware objects) through that existing
  `simulate` call signature, not to give each of ~178 tiny classes its own constructor-injected
  field.
- This is mechanical but high-volume (134 files). Do it in batches grouped by
  instruction category (arithmetic, branch, load/store, floating point, syscalls), compiling and
  running the instruction test suite after each batch.
- Once all 134 files are migrated, delete the `@Deprecated` static shims from 2.4.2 — if anything
  outside the batch still references them, the build will fail loudly, which is the desired signal.

### 2.4.4 — Fold `Memory`'s static configuration fields into instance state

- Move the ~20 `public static int` address fields (`textBaseAddress` through `kernelHighAddress`,
  plus `dataSegmentLimitAddress`/`kernelTextLimitAddress`/`stackLimitAddress`/`memoryMapLimitAddress`)
  into instance fields, populated by the existing `setConfiguration()` logic but now operating on
  `this` instead of static state.
- Search separately for any direct static reads of these fields outside `Memory` itself
  (e.g. `Memory.textBaseAddress` used elsewhere — `RegisterFile`'s `programCounter` register is
  initialized from `Memory.textBaseAddress` per today's static initializer, at
  `RegisterFile.java:94`). Every such site needs the owning `Memory` instance threaded through.
- Remove the private constructor / eager-singleton pattern (`uniqueMemoryInstance`,
  `getInstance()`); make the constructor public (or package-private + factory), taking whatever
  `MemoryConfigurations`/`InstructionSet` dependency it needs per MODERNIZATION_PLAN.md's sketch
  (`new Memory(settings.getMemoryConfiguration(), instructionSet)`).
- Same bridging discipline as 2.4.2: keep a temporary `@Deprecated static Memory getInstance()`
  that returns a process-global instance, so the 66 `Globals.memory` call sites don't need to move
  in the same commit.

### 2.4.5 — Flesh out `MarsContext` and the Dagger graph

- Add `memory`, `registerFile`, `coprocessor0`, `coprocessor1`, `instructionSet` as final fields on
  `MarsContext`, constructed in dependency order (`instructionSet` and `settings` first, then
  `memory` which needs the resolved memory configuration, then the three register-holding objects,
  matching MODERNIZATION_PLAN.md's sketch).
- Extend `CoreModule` with `@Provides` methods for `InstructionSet`, `Memory`, `RegisterFile`,
  `Coprocessor0`, `Coprocessor1`, all `@Singleton`-scoped *per component instance* (Dagger already
  gives you one `MarsContext`/graph per `MarsComponent.Builder().build()` call — that's what makes
  "two independent `MarsContext` objects in the same JVM" work for free once the graph is correct;
  no manual "process-global instance" plumbing is needed for the DI-consuming path).
- The `@Deprecated` static shims from 2.4.2/2.4.4 are a *separate* concern from the Dagger graph —
  they exist only to keep legacy non-DI call sites compiling, and should point at one specific
  "legacy" instance (see 2.4.6), not at whatever `MarsContext` a given caller happens to be using.

### 2.4.6 — Turn `Globals` into the deprecated facade the plan asks for

- Build exactly one process-global `MarsContext` inside `Globals` (constructed via
  `DaggerMarsComponent.builder().guiProxy(HEADLESS_DEFAULT).build().context()`, or similar),
  created lazily the first time any legacy static accessor is touched — mirroring the existing
  `initialize()`/`initialized` guard.
- Change `Globals.memory`, `Globals.instructionSet`, `Globals.settings`/`getSettings()`,
  `Globals.gui`/`getGui()`/`setGui()` to delegate to that process-global `MarsContext`'s fields
  instead of independently constructing their own objects. `Globals.program`, `Globals.symbolTable`,
  `Globals.debug`, `Globals.exitCode`, `Globals.runSpeedPanelExists`,
  `Globals.memoryAndRegistersLock` stay as-is (they're per-assembly-run or per-process state, not
  simulation hardware, and are outside 2.4's stated scope).
- Mark the class `@Deprecated` at the type level (or at minimum each delegating field/method) per
  the plan's wording ("the deprecated `Globals` facade").
- Point the `@Deprecated` static shims added in 2.4.2/2.4.4 at this same process-global
  `MarsContext`, so there is exactly one legacy-compatible instance in the whole process, not one
  per shim class.

### 2.4.7 — Migrate `Assembler`, `Simulator`, `BackStepper` to constructor injection

Per MODERNIZATION_PLAN.md's explicit list, in this order (each is a prerequisite for cleanly
testing the next):

1. `BackStepper(MarsContext ctx)` — smallest surface area (one file, ~15 call sites inside itself).
2. `Assembler(MarsContext ctx, List<Path> sources)` — replace every `Globals.symbolTable`,
   `Globals.memory`, `Globals.debug`, `Globals.instructionSet`, `Globals.getSettings()` reference
   with `ctx.symbolTable` (new field, currently not on `MarsContext` — add it), `ctx.memory`, a
   `ctx.settings`/debug-flag equivalent, `ctx.instructionSet`. Note `Assembler`'s current signature
   doesn't take a `List<Path> sources` either — check current constructor/entry method shape before
   assuming the plan's sketch signature matches; adapt the existing API rather than force-fitting
   the plan's illustrative one if they diverge.
3. `Simulator(MarsContext ctx, SyscallEnvironment env)` — do this *before* the §2.8 threading
   rewrite, touching only the singleton-removal and constructor-injection parts. Leave
   `SimThread extends SwingWorker` untouched here.

For each of the three, keep call sites that can't be migrated yet working via the `Globals` facade
(e.g. a legacy caller can still do `new Assembler(Globals.context(), ...)` if you expose a package-
or module-visible accessor for the process-global context — don't make it a public, encouraged API,
just enough to unblock incremental migration).

### 2.4.8 — Migrate entry points off `Globals.initialize()`

In order of blast radius (smallest/most isolated first):

1. The 4 test classes (`AssemblerSmokeTest`, `InstructionSetTest`, `SimulatorSmokeTest`,
   `MemoryTest`) — switch to building a `MarsComponent` directly and using its `MarsContext`. This
   is also the first real exercise of the previously-dead DI scaffolding.
2. `AbstractMarsToolAndApplication.java:153` — base class for MARS tools; migrate once tools are
   confirmed to work against `MarsContext` in tests.
3. `MarsLaunch.java:135` — batch/CLI launch path.
4. `VenusUI.java:168` — main GUI window; do this last since it's the highest-traffic, hardest to
   fully manually test path (the `plugin` module doesn't have Venus-specific automated coverage per
   the earlier architecture map).

## 3. New tests to write (this is the actual acceptance criterion for 2.4)

- **Isolation test** (the core promise of this step): build two independent `MarsComponent`
  instances (two `MarsContext`s), assemble+run two different trivial MIPS programs — one that
  writes to `$t0` and memory address `0x10010000`, another that writes different values to the same
  register number and address — and assert neither context observes the other's state. This is the
  test that proves 2.4 is actually done, as opposed to "compiles and old behavior still works."
- Unit tests for `RegisterFile`/`Coprocessor0`/`Coprocessor1` once instantiable: construct two
  instances, mutate one, assert the other is unaffected.
- Unit test for `Memory` once its address-configuration fields are instance-scoped: construct two
  `Memory` instances with different `MemoryConfiguration`s, assert each keeps its own
  `textBaseAddress`/`stackBaseAddress`/etc.
- Regenerate/rerun the existing `AssemblerSmokeTest`, `InstructionSetTest`, `SimulatorSmokeTest`,
  `MemoryTest` after each sub-step in §2 — they're the regression safety net for the legacy
  `Globals`-facade path throughout the migration.

## 4. Explicitly out of scope for this plan

- §2.8 (Simulator `SwingWorker` → modern threading) — only the constructor-injection part of
  `Simulator` is in scope here; sequence §2.8 as a follow-up once 2.4 lands, per §1.4 above.
- §2.5/2.6/2.7 (`InstructionSet.java`/`Memory.java`/`Assembler.java` internal refactors into smaller
  files) — those are structural splits of already-injected code and should happen after 2.4, not
  interleaved with it, to keep diffs reviewable.
- Migrating `Globals.program`, `Globals.symbolTable` (as a *field*, distinct from `Assembler`'s use
  of it), `Globals.debug`, `Globals.exitCode`, `Globals.runSpeedPanelExists` into `MarsContext` —
  MODERNIZATION_PLAN.md's own field list for `MarsContext` doesn't include these, and they read as
  per-run/per-process concerns rather than simulator hardware state. Revisit only if a later phase's
  plan explicitly calls for it.

## 5. Risk register

| Risk | Where it bites | Mitigation |
|---|---|---|
| 134-file instruction migration (2.4.3) introduces a subtle register/coprocessor aliasing bug | Any instruction whose impl class caches a `RegisterFile` reference incorrectly across calls | Batch by instruction category, run the full instruction test suite (the plan's own §2.5 calls for ~178 instruction test cases — write/borrow as many as exist before starting 2.4.3) after each batch |
| `Settings`/`SimulationSettings` reconciliation (2.4.1) changes observable GUI behavior | Venus GUI settings dialog, any persisted user preferences | Do this step in isolation with its own PR/review before any hardware-class work starts |
| Two "legacy compatibility" instances accidentally created (one via `Globals`, one via a shim added ad hoc) | 2.4.2/2.4.4/2.4.6 | Route *all* deprecated static shims through the single process-global `MarsContext` built in 2.4.6 — don't let 2.4.2/2.4.4 each invent their own holder |
| `Simulator` singleton removal collides with in-flight §2.8 threading work | `Simulator.java` | Land 2.4.7's `Simulator` change first and get it merged before starting §2.8, per §1.4 |
| No existing Venus/plugin UI automated test coverage means entry-point migration (2.4.8 step 4) regressions go unnoticed | `VenusUI.java` | Manual smoke test of the plugin's Run/Debug tool window after this step, in addition to unit tests elsewhere |

## 6. Suggested commit sequence

1. Reconcile `Settings`/`SimulationSettings` (2.4.1)
2. Convert `RegisterFile` to instance + deprecated static shim (2.4.2, one class)
3. Convert `Coprocessor0` to instance + deprecated static shim (2.4.2, one class)
4. Convert `Coprocessor1` to instance + deprecated static shim (2.4.2, one class)
5. Migrate instruction implementations off shims, batch 1..N (2.4.3, multiple commits)
6. Remove `RegisterFile`/`Coprocessor0`/`Coprocessor1` deprecated shims once batch migration is complete
7. Fold `Memory` static config into instance state + deprecated `getInstance()` shim (2.4.4)
8. Flesh out `MarsContext` + `CoreModule` providers (2.4.5)
9. Turn `Globals` into the delegating facade (2.4.6)
10. `BackStepper` constructor injection (2.4.7.1)
11. `Assembler` constructor injection (2.4.7.2)
12. `Simulator` constructor injection only, no threading changes (2.4.7.3)
13. Migrate test entry points to `MarsComponent` (2.4.8.1)
14. Migrate `AbstractMarsToolAndApplication`, `MarsLaunch`, `VenusUI` (2.4.8.2–4)
15. Add the two-`MarsContext`-isolation test (§3) and any remaining unit tests
16. Remove `Memory.getInstance()` deprecated shim once no callers remain
