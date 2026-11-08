# MARS MIPS Simulator — Modernization Plan

**Date:** 2026-05-03  
**Scope:** Full modernization from legacy Swing/batch-script monolith to a Gradle multi-module project with a clean core library and an IntelliJ IDE plugin.  
**Codebase baseline:** 217 Java files, ~60,400 LOC, no tests, no build tool, Java 5–8 era code.

**Current status (2026-11-08): Phase 2.5 complete; Kotlin plugin bootstrap integrated.**
The implemented modules are `mars5/core`, `mars5/cli`, `mars5/desktop`, and `mars5/plugin`.
The compatibility runtime has been deleted. Core is independent of both desktop and IntelliJ;
all frontends use explicit session ownership. The plugin is Kotlin; core, CLI, and desktop are
currently Java. All modules target JDK 25 with Gradle 9.9.

**MARS 5 destination:** fully Kotlin engine, CLI, and IntelliJ plugin. The next engine milestone
ports the tested core to idiomatic Kotlin in coherent subsystems, preserving MIPS semantics and
session isolation. Begin with value types/contracts, then hardware, instruction execution,
assembly, simulation/I/O, and CLI. The current Java implementation provides the behavioral
reference during migration. Keep each increment green; avoid retaining Java-shaped compatibility
facades in the new Kotlin design. Kotlin plugin language/UI work can build on the stable session
boundary, while Venus remains available in the separate desktop module.

See [Phase 2.5 completion and acceptance evidence](PHASE_2_5_PLAN.md). Earlier phase sketches below
are historical design notes where they differ from this status and the checked-in build files.


---

## Table of Contents

1. [Guiding Principles](#guiding-principles)
2. [Target Architecture](#target-architecture)
3. [Phase 0 — Gradle Migration](#phase-0--gradle-migration)
4. [Phase 1 — Core Isolation and Interface Design](#phase-1--core-isolation-and-interface-design)
5. [Phase 2 — Core Rewrite](#phase-2--core-rewrite)
   - [2.0 — Decouple Globals from VenusUI](#20--decouple-globals-from-venusui-prerequisite-for-all-module-moves)
6. [Phase 2.5 — Legacy Deprecation Cleanup](#phase-25--legacy-deprecation-cleanup)
7. [Phase 3 — IntelliJ Plugin](#phase-3--intellij-plugin)
8. [Cross-Cutting Concerns](#cross-cutting-concerns)
9. [Risk Register](#risk-register)
10. [Dependency Map (Phases)](#dependency-map-phases)

---

## Guiding Principles

- **Do not break the assembler or simulator semantics.** MIPS programs that run correctly today must run correctly after the rewrite. This is the single hard invariant.
- **Incremental, always-green.** Each phase ends with a working build. No "big bang" rewrite.
- **Tests before refactoring, not after.** Capture existing behavior as characterization tests before changing anything.
- **Interfaces before implementations.** Define the public API surface of the core before rewriting internals.
- **Kotlin for MARS 5.** Write new engine and IntelliJ implementation in idiomatic Kotlin; migrate the existing Java core against its behavior tests.
- **No new Swing.** The existing Venus GUI is frozen: bug-fix only. All new UI goes into the IntelliJ plugin.

---

## Target Architecture

```text
mars5/
├── core/       independent engine; Java behavioral reference, Kotlin migration next
├── cli/        standalone launcher; migrates with the engine
├── desktop/    existing Venus frontend; depends only on core
├── plugin/     Kotlin IntelliJ plugin; depends only on core
├── build.gradle.kts
└── settings.gradle.kts
```

The final engine and CLI source roots will be Kotlin. The desktop transition frontend is kept
separate from the plugin artifact and from the core dependency graph.

---

## Phase 0 — Gradle Migration

**Goal:** Replace `CreateMarsJar.bat` and the ad-hoc IntelliJ module file with a proper Gradle build. This phase changes no source code; it only changes the build infrastructure.

**Prerequisites:** None. Start here.

### 0.1 — Repository setup

- Delete `CreateMarsJar.bat`, `mars-original.iml`, and the root-level `META-INF/` folder (Gradle will generate the manifest).
- Keep all `.java` source files, `.properties` files, `.txt` resource files, `images/`, `help/`, and XML data files in place until Phase 1 reorganizes them.

### 0.2 — Gradle wrapper

```
gradle wrapper --gradle-version=8.8 --distribution-type=bin
```

Commit the wrapper scripts (`gradlew`, `gradlew.bat`, `gradle/wrapper/`).

### 0.3 — `settings.gradle.kts`

```kotlin
rootProject.name = "mars"

// Subprojects defined now; source roots filled in later phases
include("mars-core")
include("mars-cli")
include("mars-plugin")
```

For Phase 0, create stub `build.gradle.kts` files for each subproject (empty `plugins {}` block only). The actual source reorganization happens in Phase 1.

### 0.4 — Root `build.gradle.kts`

```kotlin
plugins {
    java apply false
}

subprojects {
    apply(plugin = "java")
    
    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    
    repositories {
        mavenCentral()
    }
    
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
```

### 0.5 — Temporary monolithic build (bridge)

While source still lives in the root tree, add a temporary `app` subproject whose `sourceSets.main.java.srcDirs` point to the existing `mars/` directory so you can verify the build compiles cleanly before moving anything:

```kotlin
// app/build.gradle.kts  (temporary bridge module)
plugins { java }

sourceSets {
    main {
        java { srcDirs("../mars") }
        resources { srcDirs("..") }  // picks up .properties, images, help
    }
}

tasks.jar {
    manifest { attributes("Main-Class" to "edu.missouristate.mars.plugin.Mars") }
    from(sourceSets.main.get().output)
    archiveFileName = "mars.jar"
}
```

**Verify:** `./gradlew :app:jar` should produce an equivalent `mars.jar`.

### 0.6 — CI skeleton

Create `.github/workflows/build.yml`:

```yaml
name: Build
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - run: ./gradlew build
```

**Phase 0 exit criteria:**
- `./gradlew :app:jar` produces a runnable MARS jar (all existing functionality works).
- CI passes on every commit.

---

## Phase 1 — Core Isolation and Interface Design

**Goal:** Draw a hard boundary between the simulation core and all UI code. Define the API surface the plugin will depend on. Move source files into the multi-module structure. **Do not yet rewrite any logic** — only move and extract interfaces.

**Prerequisites:** Phase 0 complete.

### 1.1 — Package rename (`mars` → `edu.missouristate.mars`)

Before touching any logic or writing tests, rename every Java package from the flat `mars.*` namespace to the proper reverse-domain hierarchy `edu.missouristate.mars.*`. This is a mechanical, semantics-preserving change that must come first so all subsequent work (tests, interface definitions, module moves) targets the final package names.

**Rename map:**

| Old package | New package |
|---|---|
| `mars` | `edu.missouristate.mars` |
| `mars.assembler` | `edu.missouristate.mars.assembler` |
| `mars.mips` | `edu.missouristate.mars.mips` |
| `mars.mips.hardware` | `edu.missouristate.mars.mips.hardware` |
| `mars.mips.instructions` | `edu.missouristate.mars.mips.instructions` |
| `mars.mips.dump` | `edu.missouristate.mars.mips.dump` |
| `mars.simulator` | `edu.missouristate.mars.simulator` |
| `mars.util` | `edu.missouristate.mars.util` |
| `mars.venus` | `edu.missouristate.mars.plugin.venus` |
| `mars.venus.editors` | `edu.missouristate.mars.plugin.venus.editors` |
| `mars.venus.editors.jeditsyntax` | `edu.missouristate.mars.plugin.venus.editors.jeditsyntax` |
| `mars.tools` | `edu.missouristate.mars.plugin.tools` |

**Procedure:**

1. Use the IDE's "Rename Package" refactoring on the root `mars` package with "Rename subpackages" checked. The IDE rewrites every `package` declaration and `import` statement automatically.
2. Verify with:
   ```bash
   grep -rn "^package mars" src/
   grep -rn "^import mars\." src/
   ```
   Both should return zero results after the rename.
3. Update `edu.missouristate.mars.plugin.Mars.java`'s main-class reference in any manifest or launch script.
4. Update the `app/build.gradle.kts` bridge module if it references the old `mars/` directory name as a source root.
5. Commit the rename as a single isolated commit with message `refactor: rename packages mars.* → edu.missouristate.mars.*`.

**Verify:** `./gradlew :app:jar` still produces a runnable jar after the rename.

---

### 1.2 — Characterization test suite (write first, before moving anything)

Before touching source layout, write characterization tests against the existing code. These tests are the safety net for all later phases.

Create `app/src/test/java/edu/missouristate/mars/` with at least:

| Test class | What it pins |
|---|---|
| `AssemblerSmokeTest` | Assemble 20–30 canonical MIPS programs; verify no errors and expected binary output. |
| `SimulatorSmokeTest` | Run assembled programs to completion; verify register and memory state. |
| `InstructionSetTest` | Verify every instruction in `InstructionSet` has a non-null mnemonic, a non-null format, and passes `BasicInstruction.getExampleFormat()`. |
| `MemoryTest` | Read/write at each memory segment boundary; verify address translation. |
| `SyscallListTest` | Verify all syscalls listed in `Syscall.properties` are loadable. |

Use JUnit 5 (`junit-jupiter:5.11`) and AssertJ for assertions. These tests should all pass on the unmodified source before you move a single file.

#### 1.2.1 — Test gap analysis (produce alongside the smoke tests)

While writing characterization tests you will inevitably encounter areas that are untested or hard to pin with a smoke test. Track these explicitly: create `TEST_GAPS.md` at the repo root and populate it as you go. This document becomes the unit-test backlog for Phase 2.

For each gap, record:
- **Area** — the class or subsystem
- **What is uncovered** — the behavior, edge case, or code path
- **Why it matters** — consequence of a silent regression
- **Suggested test type** — unit / integration / parameterized / property-based

Seed the file with the known high-risk areas found during the smoke test pass:

| Area | Known gaps to document |
|---|---|
| `BasicInstruction` | Per-instruction encoding mask correctness; simulation semantics for each of 155 registered basic instruction implementations |
| `Memory` | All boundary addresses per `MemoryConfiguration`; aligned vs. unaligned access; segment overlap edge cases |
| `Assembler` | Macro expansion; `.eqv` / `.set` substitution; forward-reference resolution; error-message content |
| `PseudoInstructions` | Each extended instruction's expansion — verify the generated basic instruction sequence |
| `BackStepper` | Undo correctness after every instruction class that modifies state |
| `Coprocessor1` | IEEE 754 edge cases: NaN, ±Inf, denormals, rounding modes |
| `Syscalls` | All 45 syscall behaviors against a headless `SyscallEnvironment` stub |
| `SymbolTable` | Duplicate label detection; global vs. local scope; forward references |
| `Tokenizer` | All comment styles; string/char escape sequences; edge-case integer literals (hex, octal, negative) |

`TEST_GAPS.md` is a living document — update it whenever new gaps are found during Phase 2 work.

### 1.3 — Define core API interfaces

Create `mars-core/src/main/java/edu/missouristate/mars/api/`. These interfaces describe what the plugin and CLI will use — they must not import anything from `javax.swing` or `java.awt`.

```
edu.missouristate.mars.api/
  MarsAssembler.java      — assemble(List<Path> sources): AssemblyResult
  MarsSimulator.java      — step(), run(), stop(), pause(), backstep()
  SimulatorListener.java  — onStep(StepEvent), onStop(StopEvent), onError(ErrorEvent)
  MarsMemory.java         — getWord(int addr), setWord(int addr, int val), etc.
  MarsRegisterFile.java   — getValue(int reg), setValue(int reg, int val), getPC()
  AssemblyResult.java     — record: List<AssemblyError> errors, Program program
  AssemblyError.java      — record: Path file, int line, String message
  StepEvent.java          — record: int pc, int instruction, long stepCount
  StopEvent.java          — enum reason: NORMAL_EXIT, BREAKPOINT, PAUSE, EXCEPTION
  ErrorEvent.java         — record: String message, Throwable cause
  MarsSettings.java       — interface for headless-safe settings queries
```

None of these interfaces contain implementation — they are the contracts.

### 1.4 — Identify and quarantine GUI dependencies in the core

Run a dependency audit:

```bash
grep -rn "javax.swing\|java.awt\|edu.missouristate.mars.plugin.venus" \
  src/edu/missouristate/mars/assembler \
  src/edu/missouristate/mars/mips \
  src/edu/missouristate/mars/simulator \
  src/edu/missouristate/mars/util
```

Key coupling points found in the existing code:

| Class | Problem |
|---|---|
| `Globals.java` | Holds both simulation state AND `VenusUI gui` reference |
| `Simulator.java` | Uses custom `SwingWorker` for threading |
| `Settings.java` | Mixes simulation config with Swing font/color settings |
| `SyscallLoader.java` | Instantiates GUI dialog syscalls via reflection |
| `AbstractSyscall` subclasses | ~12 of 45 syscalls use `JOptionPane` or `JDialog` |
| `MarsLaunch.java` | GUI startup logic mixed with CLI parsing |

For each: do NOT refactor yet. Add a `// FIXME(phase-2): gui-coupling` comment so they are grep-able.

### 1.5 — Split `Globals.java`

`Globals.java` is the biggest blocker. Split it into two new classes:

**`edu.missouristate.mars.core.MarsContext`** (Phase 1 stub, lives in `mars-core`):
- `Memory memory`
- `RegisterFile registerFile`
- `Coprocessor0 cp0`
- `Coprocessor1 cp1`
- `InstructionSet instructionSet`
- `SymbolTable symbolTable`
- `Program program`
- `Settings settings` (headless-safe subset only)

**`edu.missouristate.mars.plugin.venus.VenusContext`** (stays in GUI layer):
- `VenusUI gui`
- All font/color settings references
- `ExternalTools externalTools`

Keep the original `Globals` class as a deprecated facade that delegates to both contexts during the transition period. Mark every method `@Deprecated`.

### 1.6 — Split `Settings.java`

`Settings.java` is 67 KB and mixes simulation settings (memory config, delayed branching, self-modifying code) with GUI settings (editor fonts, syntax highlighting colors, window sizes).

Create two classes:

**`edu.missouristate.mars.core.SimulationSettings`** (goes to `mars-core`):
- `MemoryConfiguration memoryConfig`
- `boolean delayedBranching`
- `boolean selfModifyingCode`
- `boolean warningsAreErrors`
- `int assemblerErrorLimit`
- Backed by a `java.util.Properties` file

**`edu.missouristate.mars.plugin.venus.EditorSettings`** (stays in GUI layer):
- Font names, sizes
- Syntax highlighting colors
- Window geometry
- Tab size, auto-indent

Keep `Settings` as a deprecated facade during transition.

### 1.7 — Extract a `SyscallEnvironment` interface

The core simulator should not know whether syscalls open Swing dialogs or print to stdout. Define:

```java
// edu.missouristate.mars.api.SyscallEnvironment
public interface SyscallEnvironment {
    void printString(String s);
    void printInt(int value);
    String readString(int maxLength);
    int readInt();
    // ... all I/O primitives
    void exit(int code);
    void openFile(String path, int flags);
    // ... file I/O
}
```

Provide two implementations:
- `edu.missouristate.mars.cli.ConsoleSyscallEnvironment` — reads/writes `System.in`/`System.out`
- `edu.missouristate.mars.plugin.ToolWindowSyscallEnvironment` — writes to plugin console tool window

Delete the GUI dialog syscalls (`InputDialogInt`, `InputDialogString`, `ConfirmDialog`, `MessageDialog`) once the plugin implementation exists.

### 1.8 — Move source into the multi-module layout

Only move files; do not change them. Use `git mv` to preserve history.

| Source | Destination module |
|---|---|
| `edu/missouristate/mars/assembler/` | `mars-core` |
| `edu/missouristate/mars/mips/` | `mars-core` |
| `edu/missouristate/mars/simulator/` | `mars-core` |
| `edu/missouristate/mars/util/` | `mars-core` |
| `edu/missouristate/mars/Globals.java` (facade) | `mars-core` (temporary) |
| `edu/missouristate/mars/MarsLaunch.java` (CLI portion) | `mars-cli` |
| `edu/missouristate/mars/venus/` | `mars-plugin` (temporary; eventually replaced) |
| `edu.missouristate.mars.plugin.Mars.java`, `mainclass.txt` | `mars-cli` |
| `*.properties`, `PseudoOps.txt` | `mars-core/src/main/resources/edu/missouristate/mars/` |
| `images/`, `help/` | `mars-plugin/src/main/resources/` |
| XML data files | `mars-core/src/main/resources/edu/missouristate/mars/` |

Update `build.gradle.kts` in each subproject to declare the right dependencies:
- `mars-cli` depends on `mars-core`
- `mars-plugin` depends on `mars-core` (and IntelliJ Platform SDK, added in Phase 3)

**Phase 1 exit criteria:**
- All packages renamed to `edu.missouristate.mars.*`; zero `package mars` / `import mars.` occurrences remain.
- `TEST_GAPS.md` exists and enumerates the known unit-test backlog.
- `./gradlew build` compiles all three modules.
- All characterization tests from 1.2 still pass.
- `./gradlew :mars-cli:jar` produces a runnable CLI jar.
- No `javax.swing` or `java.awt` imports in `mars-core`.

---

## Phase 2 — Core Rewrite

**Goal:** Eliminate deprecated constructs, add generics, replace the `Observable` pattern, apply dependency injection, and bring the core to clean modern Java 21. The core must remain headless (no GUI dependencies).

**Prerequisites:** Phase 1 complete (clean module boundaries, characterization tests passing).

Work through the core subsystems in this order (each is a separate PR):

### 2.0 — Decouple `Globals` from `VenusUI` (prerequisite for all module moves)

This is the single most important step in Phase 2 and must be completed before any source file can physically move from the root module into `mars-core`. The blocker is `Globals.java`, which holds a direct compile-time reference to `VenusUI` — a Swing class that will live in `mars-plugin`. Until that reference is replaced by an interface defined in `mars-core`, the compiler dependency graph prevents the split.

#### 2.0.1 — Audit all `Globals.getGui()` call sites

Run:
```bash
grep -rn "Globals.getGui()" src/main/java --include="*.java"
```

Classify every call site into one of two categories:

**Category A — Core packages (need abstraction; currently preventing module move):**

| Class | Call | Why it is a problem |
|---|---|---|
| `simulator.Simulator` | `.getRegistersPane()`, `.getMainPane()`, `.getExecutePane()`, `.getTextSegmentWindow()`, `.highlightStepAtPC()` | 8+ distinct GUI calls; drives register/memory panel repaints directly from the sim loop |
| `util.SystemIO` | `.getMessagesPane().getInputString()`, `.getMessagesPane().postRunMessage()` | Console I/O routing for 5 read/write methods |
| `mips.instructions.syscalls.SyscallClearScreen` | `.getMessagesPane().getRunTextArea().setText("")` | Direct JTextArea manipulation from a syscall |
| `mips.instructions.syscalls.SyscallExit2` | `== null` null-check | Used only as a headless-mode flag |
| `mips.hardware.Memory` | `== null` null-check | Used only as a headless-mode flag |

**Category B — Tools and venus packages (no abstraction needed; will move to `mars-plugin`):**

All callers in `edu.missouristate.mars.plugin.tools.*` (`DigitalLabSim`, `FloatRepresentation`, `KeyboardAndDisplaySimulator`, `ScreenMagnifier`, `ScavengerHunt`, `MipsXray`, `AbstractMarsToolAndApplication`) and `edu.missouristate.mars.plugin.venus.*`. These classes are already GUI-only and will move to `mars-plugin` as a unit. They can continue calling `Globals.getGui()` returning `VenusUI` without any change until Phase 3 removes them.

Only Category A callers need to be fixed in this step.

#### 2.0.2 — Define `MarsGuiProxy` in `mars-core`

Create `mars-core/src/main/java/edu/missouristate/mars/api/MarsGuiProxy.java`:

```java
package edu.missouristate.mars.api;

/**
 * Minimal GUI abstraction visible to mars-core. Implementations:
 *   - VenusUI (mars-plugin) — wraps MessagesPane and window panes
 *   - HeadlessGuiProxy (mars-cli) — routes to System.in / System.out
 */
public interface MarsGuiProxy {
    /** True when running with a Swing GUI; false in headless/CLI mode. */
    boolean isGuiMode();

    /**
     * Post a message to the "MARS Messages" console (the non-program output area).
     * In headless mode, writes to System.err.
     */
    void postMessage(String message);

    /**
     * Post a string to the "Run I/O" console (the program stdout area).
     * In headless mode, writes to System.out.
     */
    void postRunMessage(String message);

    /**
     * Read a string from the "Run I/O" console (program stdin).
     * @param maxLength Maximum characters to read; -1 means read until newline.
     * In headless mode, reads from System.in.
     */
    String readInput(int maxLength);

    /**
     * Clear the "Run I/O" console. No-op in headless mode.
     */
    void clearRunConsole();
}
```

This interface deliberately does not expose any Swing types. It covers exactly the operations used by Category A callers.

#### 2.0.3 — Define `SimulatorObserver` in `mars-core`

`Simulator.java` is the deepest coupling: it calls 8+ different GUI methods to repaint register windows, memory segments, and highlight the current PC line after each simulation step. These cannot be routed through `MarsGuiProxy` without making the interface GUI-aware.

Instead, introduce a callback interface that `Simulator` fires after each step, and let `VenusUI` subscribe to it and do its own repainting:

Create `mars-core/src/main/java/edu/missouristate/mars/api/SimulatorObserver.java`:

```java
package edu.missouristate.mars.api;

/**
 * Receives notifications from the simulator after each step and on stop.
 * VenusUI registers itself to refresh the register, memory, and text
 * segment windows. The CLI implementation is a no-op.
 */
public interface SimulatorObserver {
    /**
     * Called after each instruction is executed.
     * @param pc The program counter value of the instruction just executed.
     */
    void onStepComplete(int pc);

    /**
     * Called when the simulation halts for any reason (normal exit, exception,
     * pause, max-steps reached).
     * @param reason One of: NORMAL_EXIT, EXCEPTION, PAUSE, MAX_STEPS
     */
    void onSimulationStop(StopEvent reason);
}
```

Add registration to `Simulator`:

```java
// In Simulator.java — replacing the eight direct GUI calls in the inner run loop
private SimulatorObserver observer = null;

public void setObserver(SimulatorObserver observer) {
    this.observer = observer;
}

// In the step-execution loop, replace all getGui().getRegistersPane()... calls with:
if (observer != null) {
    observer.onStepComplete(RegisterFile.getProgramCounter());
}
```

In `VenusUI`, after creating the `Simulator` instance:

```java
Simulator.getInstance().setObserver(new SimulatorObserver() {
    @Override
    public void onStepComplete(int pc) {
        SwingUtilities.invokeLater(() -> {
            // All the repaint calls that used to be inside Simulator:
            if (getRegistersPane().getSelectedComponent() == getMainPane()
                    .getExecutePane().getRegistersWindow()) {
                getMainPane().getExecutePane().getRegistersWindow().updateRegisters();
            } else {
                getMainPane().getExecutePane().getCoprocessor1Window().updateRegisters();
            }
            getMainPane().getExecutePane().getDataSegmentWindow().updateValues();
            getMainPane().getExecutePane().getLabelsWindow().updateLabelAddresses();
            getMainPane().getExecutePane().getTextSegmentWindow().highlightStepAtPC();
        });
    }

    @Override
    public void onSimulationStop(StopEvent reason) {
        SwingUtilities.invokeLater(() -> {
            // Any teardown repaints — e.g. clear step highlight on normal exit
        });
    }
});
```

After this change, `Simulator.java` contains zero references to any `mars.venus` class.

#### 2.0.4 — Provide `HeadlessGuiProxy` in `mars-cli`

Create `mars-cli/src/main/java/edu/missouristate/mars/cli/HeadlessGuiProxy.java`:

```java
package edu.missouristate.mars.cli;

import edu.missouristate.mars.api.MarsGuiProxy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** MarsGuiProxy implementation for headless / command-line execution. */
public final class HeadlessGuiProxy implements MarsGuiProxy {
    private final BufferedReader stdin = new BufferedReader(
        new InputStreamReader(System.in));

    @Override public boolean isGuiMode() { return false; }

    @Override public void postMessage(String message) {
        System.err.print(message);
    }

    @Override public void postRunMessage(String message) {
        System.out.print(message);
    }

    @Override public String readInput(int maxLength) {
        try {
            String line = stdin.readLine();
            if (line == null) {
              return "";
            }
            return maxLength < 0 ? line
                : line.substring(0, Math.min(line.length(), maxLength));
        } catch (IOException e) {
            return "";
        }
    }

    @Override public void clearRunConsole() { /* no-op */ }
}
```

In `MarsLaunch` (CLI entry point), set this before calling `Globals.initialize()`:

```java
Globals.setGui(new HeadlessGuiProxy());
```

#### 2.0.5 — Make `VenusUI` implement `MarsGuiProxy`

In `VenusUI.java`:

```java
public class VenusUI extends JFrame implements MarsGuiProxy {

    @Override
    public boolean isGuiMode() { return true; }

    @Override
    public void postMessage(String message) {
        getMessagesPane().postMarsMessage(message);
    }

    @Override
    public void postRunMessage(String message) {
        getMessagesPane().postRunMessage(message);
    }

    @Override
    public String readInput(int maxLength) {
        return getMessagesPane().getInputString(maxLength);
    }

    @Override
    public void clearRunConsole() {
        getMessagesPane().getRunTextArea().setText("");
    }
}
```

`VenusUI` gains the interface but loses nothing — all four methods delegate to the existing `MessagesPane` calls that were previously scattered across `SystemIO` and `SyscallClearScreen`.

#### 2.0.6 — Change `Globals.gui` from `VenusUI` to `MarsGuiProxy`

In `Globals.java`:

```java
// Before:

import edu.missouristate.mars.plugin.venus.*;
...
static VenusUI gui = null;

public static void setGui(VenusUI g) {
   gui = g;
}

public static VenusUI getGui() {
   return gui;
}

// After:
import edu.missouristate.mars.api.MarsGuiProxy;
...
static MarsGuiProxy gui = null;

public static void setGui(MarsGuiProxy g) {
   gui = g;
}

public static MarsGuiProxy getGui() {
   return gui;
}
```

Remove `import edu.missouristate.mars.plugin.venus.*` from `Globals.java`. This is the commit that breaks the compile-time dependency between `mars-core` and `mars-plugin`. After this change, `Globals.java` can be moved to `mars-core`.

#### 2.0.7 — Update Category A callers to use `MarsGuiProxy`

Fix each core caller in turn. After each fix, run `./gradlew compileJava` to confirm no regressions.

**`util.SystemIO`:**

Replace every `Globals.getGui().getMessagesPane().*` call with the corresponding `MarsGuiProxy` method:

```java
// Before:
if (Globals.getGui() == null) {
    // stdin path
} else {
    Globals.getGui().getMessagesPane().postRunMessage(string);
}

// After:
MarsGuiProxy proxy = Globals.getGui();
if (!proxy.isGuiMode()) {
    // stdin path (unchanged)
} else {
    proxy.postRunMessage(string);
}
```

Apply the same pattern to all five read/write methods in `SystemIO`.

**`mips.instructions.syscalls.SyscallClearScreen`:**

```java
// Before:
if (Globals.getGui() == null) return;
JTextArea runConsole = Globals.getGui().getMessagesPane().getRunTextArea();
runConsole.setText("");

// After:
Globals.getGui().clearRunConsole();
```

**`mips.instructions.syscalls.SyscallExit2` and `mips.hardware.Memory`:**

Both use `Globals.getGui() == null` purely as a headless-mode flag. Replace with:

```java
// Before:
if (Globals.getGui() == null) { /* headless path */ }

// After:
if (!Globals.getGui().isGuiMode()) { /* headless path */ }
```

Note: `Globals.getGui()` will never be null after this change — `HeadlessGuiProxy` is registered before `Globals.initialize()` in both CLI and GUI startup paths. The null check becomes a mode check.

#### 2.0.8 — Verify the dependency cut is complete

After all sub-steps above are complete:

```bash
# Must return zero results:
grep -rn "import edu.missouristate.mars.plugin.venus" \
  src/main/java/edu/missouristate/mars/assembler \
  src/main/java/edu/missouristate/mars/mips \
  src/main/java/edu/missouristate/mars/simulator \
  src/main/java/edu/missouristate/mars/util \
  src/main/java/edu/missouristate/mars/Globals.java

# Must return zero results:
grep -rn "javax.swing\|java.awt" \
  src/main/java/edu/missouristate/mars/assembler \
  src/main/java/edu/missouristate/mars/mips \
  src/main/java/edu/missouristate/mars/simulator \
  src/main/java/edu/missouristate/mars/Globals.java
```

Run `./gradlew build` and verify all 60 characterization tests still pass. `Globals.java`, all of `simulator.*`, `util.SystemIO`, and the syscall classes are now ready to move to `mars-core` in step 1.8 (or its Phase 2 equivalent if the move was deferred).

**Step 2.0 exit criteria:**
- `Globals.getGui()` returns `MarsGuiProxy`, not `VenusUI`.
- `Globals.java` has no `import edu.missouristate.mars.plugin.venus.*`.
- `Simulator.java` has no direct calls to any `mars.venus` method; all GUI updates are fired through `SimulatorObserver`.
- `SystemIO.java`, `SyscallClearScreen.java`, `SyscallExit2.java`, `Memory.java` contain no `mars.venus` imports.
- `VenusUI` implements `MarsGuiProxy`; the four methods delegate to `MessagesPane`.
- `HeadlessGuiProxy` is instantiated in the CLI launch path before `Globals.initialize()`.
- All 60 characterization tests pass.
- Build is clean with zero new warnings.

---

### 2.1 — Replace `Vector` and `Hashtable` with modern collections

Files affected: `Memory.java`, `Assembler.java`, `InstructionSet.java`, `SymbolTable.java`, and ~10 others.

Rules:
- Replace `Vector<T>` with `ArrayList<T>` (or `CopyOnWriteArrayList<T>` where thread safety is needed).
- Replace `Hashtable<K,V>` with `HashMap<K,V>`.
- Replace `Enumeration<E>` iterator loops with `for-each` or streams.
- Replace `.addElement()` / `.elementAt()` / `.setElementAt()` with `.add()` / `.get()` / `.set()`.

After each file: re-run characterization tests.

### 2.2 — Add generic type parameters everywhere

The 62+ raw `ArrayList()` usages need type parameters. Approach:

1. Enable the `-Xlint:rawtypes` and `-Xlint:unchecked` javac flags in `mars-core/build.gradle.kts`:
   ```kotlin
   tasks.withType<JavaCompile>().configureEach {
       options.compilerArgs.addAll(listOf("-Xlint:rawtypes", "-Xlint:unchecked"))
   }
   ```
2. Fix every warning. The build should be warning-free after this step.

Key files with most raw types: `Globals.java`, `MarsLaunch.java`, `Assembler.java`, `InstructionSet.java`.

### 2.3 — Replace `Observable`/`Observer` with typed event listeners

11 classes extend `java.util.Observable`. Replace with a minimal generic event bus:

```java
// edu.missouristate.mars.core.event.EventEmitter<T>
public final class EventEmitter<T> {
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    
    public void subscribe(Consumer<T> listener) { listeners.add(listener); }
    public void unsubscribe(Consumer<T> listener) { listeners.remove(listener); }
    public void emit(T event) { listeners.forEach(l -> l.accept(event)); }
}
```

Replace each `Observable` usage:

| Class | Events to emit |
|---|---|
| `Memory` | `MemoryWriteEvent(int address, int value, int length)` |
| `RegisterFile` | `RegisterChangeEvent(int register, int oldValue, int newValue)` |
| `Coprocessor1` | `FPRegisterChangeEvent(int register, int oldBits, int newBits)` |
| `Simulator` | `StepEvent`, `StopEvent`, `ErrorEvent` (already defined in API) |

The `AbstractMarsToolAndApplication` tools currently subscribe to `Observable` — update them to use the typed events.

### 2.4 — Dependency injection for the simulator

Replace the `Globals` singleton with constructor-injected dependencies. The goal is that you can instantiate two independent `MarsContext` objects in the same JVM (needed for tests and potentially parallel plugin simulations).

```java
// edu.missouristate.mars.core.MarsContext (replaces Globals)
public final class MarsContext {
    public final Memory memory;
    public final RegisterFile registerFile;
    public final Coprocessor0 coprocessor0;
    public final Coprocessor1 coprocessor1;
    public final InstructionSet instructionSet;
    public final SimulationSettings settings;
    
    public MarsContext(SimulationSettings settings) {
        this.settings = settings;
        this.instructionSet = new InstructionSet();
        this.memory = new Memory(settings.getMemoryConfiguration(), instructionSet);
        this.registerFile = new RegisterFile();
        this.coprocessor0 = new Coprocessor0();
        this.coprocessor1 = new Coprocessor1();
    }
}
```

Change every class that currently references `Globals.memory`, `Globals.registerFile`, etc. to accept a `MarsContext` parameter. The change propagates inward:

1. `Assembler(MarsContext ctx, List<Path> sources)` — constructor injection
2. `Simulator(MarsContext ctx, SyscallEnvironment env)` — constructor injection
3. `BackStepper(MarsContext ctx)` — constructor injection

Do this class by class. The deprecated `Globals` facade still compiles and delegates to a process-global `MarsContext` instance so existing callers in Venus (not yet migrated) don't break.

### 2.5 — Refactor `InstructionSet.java` (177 KB)

This file is a single class with ~2000 lines of manual instruction registration. Split it:

**Data-driven instruction definitions:**

Move all instruction definitions to `mars-core/src/main/resources/edu/missouristate/mars/instructions/instructions.properties` (or a YAML/JSON file if a parsing dependency is acceptable). Each instruction record contains:

```
# format: mnemonic|format|encoding-mask|description|example|SimulationCode-classname
add|R|000000 sssss ttttt ddddd 00000 100000|Addition with overflow|add $t1,$t2,$t3|AddSimulation
```

Create a loader:
```java
// edu.missouristate.mars.mips.instructions.InstructionLoader
public final class InstructionLoader {
    public List<BasicInstruction> loadBasicInstructions(ClassLoader cl) { ... }
    public List<ExtendedInstruction> loadExtendedInstructions() { ... }
}
```

Each instruction's simulation logic stays in its own small class in a package:
```
edu.missouristate.mars.mips.instructions.impl/
  AddInstruction.java
  SubInstruction.java
  LoadWordInstruction.java
  ...
```

This reduces the monolithic file to ~50 lines and makes instruction definitions individually testable.

**Write unit tests for every instruction:** Given input register/memory state, verify output state. This is 155 registered implementation cases and is the highest-value test investment in the project.

### 2.6 — Refactor `Memory.java` (74 KB)

Break it into focused classes:

```
edu.missouristate.mars.mips.hardware.memory/
  MemorySegment.java        — abstract base: address range + raw int[] storage
  TextSegment.java          — read-only after assembly
  DataSegment.java          — heap + static data
  StackSegment.java         — grows downward
  KernelSegment.java        — kernel text + kernel data
  MmioSegment.java          — memory-mapped I/O
  Memory.java               — composes all segments, owns address translation
```

`Memory` becomes a coordinator rather than a mega-class. Each segment is independently testable.

### 2.7 — Refactor `Assembler.java` (76 KB)

The two-pass assembler logic is mostly sound but hard to follow because everything is in one class. Extract:

```
edu.missouristate.mars.assembler/
  Tokenizer.java          — unchanged API, internal cleanup only
  Parser.java             — new: receives tokens, produces AST-like structure
  FirstPassProcessor.java — symbol table construction, directive processing
  SecondPassProcessor.java — machine code generation
  PseudoInstructionExpander.java — expands pseudo/extended instructions
  Assembler.java          — orchestrates the above; public API
```

The existing `Assembler.java` logic is largely correct — the goal is readability and testability, not a semantic change.

### 2.8 — Replace the `Simulator` threading model

`Simulator.java` uses a custom `SwingWorker` (inner class, pre-Java-6 era). Replace with:

```java
// edu.missouristate.mars.simulator.SimulatorEngine
public final class SimulatorEngine {
    private final MarsContext ctx;
    private final SyscallEnvironment syscalls;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "mars-simulator"));
    private volatile SimulatorState state = SimulatorState.IDLE;
    private final EventEmitter<StepEvent> onStep = new EventEmitter<>();
    private final EventEmitter<StopEvent> onStop = new EventEmitter<>();
    private final EventEmitter<ErrorEvent> onError = new EventEmitter<>();
    
    public void run(int maxSteps) { /* submit to executor */ }
    public void step() { /* submit single step */ }
    public void pause() { /* set state = PAUSING */ }
    public void stop() { /* set state = STOPPING, await executor */ }
}
```

Thread safety rules:
- All state mutations happen on the simulator executor thread.
- Event emissions are done by calling `Platform.runLater` (plugin) or enqueuing to the CLI thread.
- `BackStepper` history lives on the simulator thread; reads from UI thread are synchronized.

### 2.9 — String performance in hot paths

In `Assembler.java` and `Simulator.java`, replace `String +=` in loops with `StringBuilder`. This is a straightforward mechanical change but matters during large program assembly.

Add a benchmark using JMH for the assembler (assemble a 10,000-instruction program) so regressions are visible.

### 2.10 — Add `@Override` and clean up access modifiers

- Add `@Override` everywhere applicable (IDE can do this automatically).
- Change `public` fields to `private` with getters/setters (or records where appropriate).
- Mark utility classes `final` with private constructors.
- Use `var` in local variable declarations where the type is obvious.
- Replace `instanceof` casts with pattern-matching instanceof (Java 16+).
- Use switch expressions (Java 14+) in `BasicInstructionFormat` and elsewhere.
- Replace `new Integer(x)` / `new Double(x)` with `Integer.valueOf(x)` / `Double.valueOf(x)` (most are already replaced per recent commit history).

### 2.11 — Deprecate and remove `Globals`

Once all core classes use `MarsContext` via constructor injection, the `Globals` facade can be deleted. Any remaining Venus GUI references to `Globals` will produce compile errors — fix each by passing the `MarsContext` from the plugin's execution context.

**Phase 2 exit criteria:**
- `./gradlew :mars-core:build` compiles cleanly with zero `-Xlint:rawtypes` / `-Xlint:unchecked` warnings.
- All characterization tests still pass.
- New instruction unit tests pass (155 basic instruction semantic cases plus 155 encoding cases).
- No `java.util.Observable`, `Vector`, or `Hashtable` remaining in `mars-core`.
- No `Globals` references in `mars-core`.
- `MarsContext` can be instantiated twice in the same JVM without interference (test this explicitly).

---

## Phase 2.5 — Legacy Deprecation Cleanup

**Completed:** Deprecated usages and compatibility facades were removed, desktop session
ownership was made explicit, strict compilation was enabled, and the full build and packaged
launchers were verified. The final step imported the Kotlin plugin scaffold. See
[the completion record](PHASE_2_5_PLAN.md) for details and the historical warning inventory.

---

## Phase 3 — IntelliJ Plugin

**Goal:** A Kotlin IntelliJ plugin backed by the independent Kotlin engine. The existing Venus
frontend is retained in `desktop` while native language, execution, and debugger features arrive.

**Prerequisites:** Phase 2 and Phase 2.5 are complete. The Kotlin engine migration is now an
explicit milestone; the current Java engine remains the tested integration reference meanwhile.

### Plugin module setup (implemented)

`mars5/plugin` uses Kotlin JVM 2.4.21, IntelliJ Platform Gradle Plugin 2.20.0, JDK 25, and IntelliJ
IDEA 2026.3 (build 263). `MarsSessionService` lazily owns one core session per project and closes it
on disposal. Core has no IntelliJ dependency. The IDE supplies Kotlin runtime libraries; do not
bundle an additional standard library or coroutine runtime. The starter has no Java plugin
sources. See the checked-in Gradle build for the executable configuration.

Build with `./gradlew :plugin:buildPlugin`; a local installed SDK may be supplied with
`-PlocalIdePath="/path/to/IntelliJ IDEA.app"`. Future native UI, PSI, console, and debugger code is
Kotlin. Historical Java examples below describe feature intent and must be implemented using
current Kotlin/platform APIs, rather than copied as new Java code.

### 3.1 — MIPS Language Support (PSI layer)

This is the foundation everything else in the plugin builds on.

**File type registration** (`plugin.xml`):
```xml
<fileType name="MIPS Assembly"
          implementationClass="mars.plugin.language.MipsFileType"
          fieldName="INSTANCE"
          language="MIPS"
          extensions="s;asm"/>
```

**Lexer** (`MipsLexer.flex` — JFlex grammar):
- Tokens: `REGISTER` (`$zero`, `$t0`–`$t9`, `$s0`–`$s7`, `$a0`–`$a3`, `$v0`–`$v1`, `$at`, `$gp`, `$sp`, `$fp`, `$ra`, numeric forms `$0`–`$31`)
- Tokens: `MNEMONIC` (all instruction names from `InstructionSet`)
- Tokens: `DIRECTIVE` (`.text`, `.data`, `.word`, `.byte`, `.half`, `.ascii`, `.asciiz`, `.align`, `.space`, `.globl`, `.set`, `.eqv`, `.macro`, `.end_macro`, `.include`)
- Tokens: `LABEL_DEF` (`identifier:`)
- Tokens: `LABEL_REF` (identifier used as operand)
- Tokens: `INTEGER_LITERAL`, `FLOAT_LITERAL`, `STRING_LITERAL`, `CHAR_LITERAL`
- Tokens: `COMMENT` (`#` to end of line)
- Tokens: `COMMA`, `LPAREN`, `RPAREN`, `NEWLINE`, `WHITESPACE`

**Parser** (`MipsParser` — Grammar-Kit `*.bnf`):

```bnf
mipsFile ::= (statement | NEWLINE)*
statement ::= (LABEL_DEF WHITESPACE?)* instruction
           | (LABEL_DEF WHITESPACE?)* directive
           | COMMENT
instruction ::= MNEMONIC operandList?
directive ::= DIRECTIVE directiveArgs?
operandList ::= operand (COMMA operand)*
operand ::= REGISTER | INTEGER_LITERAL | FLOAT_LITERAL
          | LABEL_REF | offsetOperand
offsetOperand ::= INTEGER_LITERAL LPAREN REGISTER RPAREN
```

**PSI elements** (generated by Grammar-Kit plus manual wrappers):
- `MipsMnemonicElement` — links to `Instruction` from the core
- `MipsRegisterElement` — knows its register number
- `MipsLabelDefElement` — defines a symbol
- `MipsLabelRefElement` — reference to a label (resolves to `MipsLabelDefElement`)

### 3.2 — Syntax Highlighting

```java
// MipsSyntaxHighlighter extends SyntaxHighlighterBase
// Color scheme keys:
MipsColors.MNEMONIC       — bold blue
MipsColors.REGISTER       — #8A2BE2 (blueviolet)
MipsColors.DIRECTIVE      — bold orange
MipsColors.LABEL          — bold teal
MipsColors.NUMBER         — #008080
MipsColors.STRING         — green
MipsColors.COMMENT        — gray italic
```

Register `MipsSyntaxHighlighterFactory` via `<lang.syntaxHighlighterFactory>` in `plugin.xml`.

### 3.3 — Code Completion

`MipsCompletionContributor extends CompletionContributor`:

- In mnemonic position: complete all instruction mnemonics (from `InstructionSet`), show description and format in lookup element tail text.
- In register position: complete `$zero`–`$ra` with ABI names and numeric aliases.
- In directive position: complete all assembler directives.
- In label operand position: complete all defined labels in the file (and across project for `.globl` labels).

### 3.4 — Reference Resolution and Navigation

`MipsLabelRefElement.getReference()` returns a `PsiReference` that resolves to the `MipsLabelDefElement` with the matching name. This enables:
- **Ctrl+Click** / Go to Declaration
- **Find Usages** for labels
- **Rename Refactoring** — rename a label everywhere it is referenced

For cross-file label resolution: implement `MipsGlobalLabelIndex` using `FileBasedIndex` keyed by label name, scoped to `.s`/`.asm` files in the project.

### 3.5 — Error Annotation

`MipsAnnotator extends ExternalAnnotator<AssemblyResult, List<AssemblyError>>`:

- `collectInformation()`: returns the current file content.
- `doAnnotate()`: runs `MarsAssembler.assemble()` from `mars-core` on the file (headless, in a background thread). Returns error list.
- `apply()`: maps each `AssemblyError` to a `HighlightInfo` range annotation.

This gives red squiggles for assembler errors without running a full build.

Show errors as warnings by default (since many MIPS programs use undefined behaviors intentionally). Provide an inspection severity setting.

### 3.6 — Run Configuration

```xml
<!-- plugin.xml -->
<configurationType implementation="mars.plugin.run.MipsRunConfigurationType"/>
<runConfigurationProducer implementation="mars.plugin.run.MipsRunConfigurationProducer"/>
```

**`MipsRunConfiguration`** fields (shown in Run/Debug configuration dialog):
- Source files (multi-select, defaults to current file)
- Program arguments (passed as MIPS program argv)
- Memory configuration (drop-down: Default, Compact Data at Zero, Compact Text at Zero)
- Maximum steps (integer, default 500,000 — existing MARS default)
- Delayed branching (checkbox)
- Self-modifying code (checkbox)

**`MipsRunConfigurationProducer`**: auto-creates run configs from `.s`/`.asm` files.

**`MipsRunProfileState`**:
1. Create `SimulationSettings` from run config.
2. Create `MarsContext`.
3. Create `ToolWindowSyscallEnvironment` (below).
4. Assemble via `MarsAssembler`.
5. If errors: print to console and stop.
6. Run via `SimulatorEngine`.
7. Report exit code when done.

### 3.7 — Console Tool Window (I/O)

Implement `SyscallEnvironment` using IntelliJ's `ConsoleView`:

```java
// edu.missouristate.mars.plugin.toolwindow.ConsoleSyscallEnvironment
public final class ConsoleSyscallEnvironment implements SyscallEnvironment {
    private final ConsoleView console;  // injected
    private final BufferedReader inputReader;
    
    @Override
    public void printString(String s) {
        console.print(s, ConsoleViewContentType.NORMAL_OUTPUT);
    }
    
    @Override
    public String readString(int maxLength) {
        // block the simulator thread; read from console input
        return inputReader.readLine();
    }
}
```

Attach the `ConsoleView` to the run configuration's `ExecutionResult` so it appears in the standard Run tool window. This replaces the `MessagesPane` from Venus.

### 3.8 — Registers Tool Window

Create a persistent tool window (not per-run):

```xml
<toolWindow id="MIPS Registers"
            anchor="right"
            factoryClass="mars.plugin.toolwindow.RegistersToolWindowFactory"
            icon="/icons/registers.svg"/>
```

**`RegistersToolWindowFactory`** creates a `RegistersPanel` containing:
- A `JBTable` (IntelliJ's table component) with columns: Register Name | ABI Name | Value (hex) | Value (decimal)
- 32 rows for integer registers, PC, HI, LO
- A tab for Coprocessor 1 (floating-point) registers
- A tab for Coprocessor 0 (control) registers
- Values update in real-time during stepped execution by subscribing to `RegisterFile`'s `EventEmitter<RegisterChangeEvent>`

### 3.9 — Memory Tool Window

```xml
<toolWindow id="MIPS Memory"
            anchor="bottom"
            factoryClass="mars.plugin.toolwindow.MemoryToolWindowFactory"/>
```

**`MemoryPanel`**:
- A hex editor-style `JBTable`: Address | +0 | +4 | +8 | +C | ASCII
- Segment navigation (Text, Data, Stack, Kernel, MMIO) via radio buttons or tabs
- Address jump field: type an address, scroll to it
- Values update by subscribing to `Memory`'s `EventEmitter<MemoryWriteEvent>`
- Editable cells: clicking a cell allows overwriting a word value (calls `MarsContext.memory.setWord()`)

### 3.10 — Debugger Integration

Use IntelliJ's `XDebugger` framework for a first-class debug experience.

**`MipsDebugProcess extends XDebugProcess`**:
- Runs the `SimulatorEngine` in step-pause mode.
- On each step: update `XSuspendContext` with current PC.
- Implements `resume()`, `startStepOver()`, `startStepInto()` (both same for MIPS — single instruction step), `startStepOut()` (run until `jr $ra`).

**`MipsBreakpointHandler extends XBreakpointHandler`**:
- Handles `XLineBreakpoint<MipsBreakpointProperties>`.
- Maps source line → assembled instruction address using the assembler's source map.
- Registers the address set with `SimulatorEngine.setBreakpoints(Set<Integer>)`.
- Highlights the current instruction line with `XSourcePosition`.

**`MipsDebuggerEvaluator extends XDebuggerEvaluator`**:
- Evaluates register names (`$t0`, `$sp`, etc.) and hex addresses as watch expressions.
- Shows the current value from `RegisterFile` or `Memory`.

**Backstep support** (unique to MARS):
- Add a custom "Backstep" action to the debugger toolbar.
- Calls `SimulatorEngine.backstep()` which delegates to `BackStepper`.
- Updates `XSuspendContext` to the previous instruction.

### 3.11 — Settings Page

```xml
<applicationConfigurable
    parentId="tools"
    instance="mars.plugin.settings.MarsApplicationSettings"
    id="mars.settings"
    displayName="MARS MIPS Simulator"/>
```

Settings exposed:
- Default memory configuration
- Default max steps
- Assembler warnings-as-errors toggle
- Syntax highlighting color overrides

Backed by `PersistentStateComponent<MarsState>` (IntelliJ's settings persistence). Replaces the `Settings.java` mechanism for plugin users.

### 3.12 — Tool Framework Migration

The 21 MARS tools (BitmapDisplay, CacheSimulator, etc.) currently extend `AbstractMarsToolAndApplication`. 

For the plugin, adapt them as IntelliJ tool windows:
- Each tool implements `ToolWindowFactory`.
- The tool's content panel is unchanged (it's just a `JPanel`) — it can be embedded directly in a `ToolWindow`.
- The tool subscribes to `Memory` and `RegisterFile` events from the running `MarsContext`.
- Tools appear as additional panels in the "MIPS Tools" tool window group.

Prioritize the most-used tools first:
1. BitmapDisplay
2. CacheSimulator
3. DigitalLabSim
4. InstructionCounter
5. MipsXray (lowest priority — most GUI-specific)

### 3.13 — Remove Venus

Once all plugin features are feature-complete and verified:

1. Delete `mars-plugin/src/main/java/mars/venus/` entirely.
2. Delete the Venus-related resources (`images/` subset, `help/` Venus-specific pages).
3. Update `mars-cli` to not reference any Venus classes (it should already be clean after Phase 2).
4. The `mars-plugin` module no longer contains Venus code — only the IntelliJ plugin code.

**Phase 3 exit criteria:**
- Plugin installs in IntelliJ IDEA 2025.1+ without errors.
- Can open a `.s` file and get syntax highlighting, completion, and error squiggles.
- Can run a MIPS program via a Run Configuration; stdout appears in the Run tool window.
- Can debug a MIPS program with line breakpoints, step, and the Registers/Memory tool windows.
- Plugin verifier passes: `./gradlew runPluginVerifier`.
- Venus code is deleted.

---

## Cross-Cutting Concerns

### Testing strategy

| Phase | Test type | Tool |
|---|---|---|
| 0 | Build smoke test | `./gradlew build` in CI |
| 1 | Characterization tests | JUnit 5, AssertJ |
| 2 | Unit tests per instruction | JUnit 5, parameterized tests |
| 2 | Assembler integration tests | Assemble known programs, diff binary |
| 2 | Simulator integration tests | Run programs, assert register/memory state |
| 2 | Performance benchmark | JMH |
| 3 | Plugin UI tests | IntelliJ Platform test framework (`LightPlatformCodeInsightTestCase`) |
| 3 | Plugin verifier | `runPluginVerifier` Gradle task |

A curated set of MIPS test programs should be collected (e.g., sorting algorithms, recursive Fibonacci, floating-point math, all syscall types) and kept in `mars-core/src/test/resources/mips/programs/`. These are the canonical regression suite.

### Code style

- Google Java Style Guide (enforced by Spotless Gradle plugin).
- Checkstyle for naming conventions.
- Add both to the root `build.gradle.kts`:

```kotlin
plugins {
    id("com.diffplug.spotless") version "7.0.2" apply false
    id("checkstyle") apply false
}
```

### Java version targets

- `mars-core`: Java 21 source/target.
- `mars-cli`: Java 21.
- `mars-plugin`: Java 21 (IntelliJ 2025.1 runs on JBR 21).

Do not use Java 22+ preview features; they complicate toolchain requirements.

### Resource file handling

All `.properties` files (`Config.properties`, `Settings.properties`, `Syscall.properties`) move to `mars-core/src/main/resources/edu/missouristate/mars/`. All access currently done via `ClassLoader.getResourceAsStream()` or `java.util.Properties` — no path changes needed, just new classpath locations.

`PseudoOps.txt` moves with `Assembler`; `images/` and `help/` move with the plugin.

### Versioning

Adopt semantic versioning. The current codebase is effectively "4.5" (last released version). The new versioning:
- 4.6.0 — Phase 0+1 complete (Gradle + module split, no feature change)
- 4.7.0 — Phase 2 complete (core rewrite)
- 5.0.0 — Phase 3 complete (IntelliJ plugin, Venus removed)

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Characterization tests miss an assembler edge case; refactoring breaks a subtle behavior | Medium | High | Use a large corpus of real MIPS programs from MARS website and university course repos as test inputs |
| `InstructionSet.java` data migration introduces incorrect encoding masks | Medium | High | Each instruction gets a unit test that verifies the encoding against the original class before migration |
| IntelliJ Platform API changes break plugin between IDE versions | High | Medium | Pin a minimum IDE version; use `pluginVerifier` in CI against latest stable and EAP |
| `SwingWorker` replacement changes timing/thread behavior; concurrent tools break | Low | High | Keep `SimulatorEngine` single-threaded for execution; only event dispatch changes |
| Memory model refactoring changes segment boundary behavior | Medium | High | Memory segment unit tests cover all boundary addresses from `MemoryConfiguration` |
| Venus tool JPanel code doesn't embed cleanly in IntelliJ tool windows | Medium | Low | All tool panels are already `JPanel` subclasses; IntelliJ accepts arbitrary Swing components in tool windows |
| `SyscallEnvironment` abstraction doesn't cover all syscall types correctly | Low | Medium | Audit all 45 syscalls against the interface before removing the GUI dialog implementations |
| Gradle IntelliJ Platform Plugin version compatibility issues | Medium | Low | Start from the official IntelliJ Platform Plugin Template; test against target IDE early |

---

## Dependency Map (Phases)

```
Phase 0 (Gradle)
    ↓
Phase 1 (Module split + API interfaces + characterization tests)
    ↓
Phase 2 (Core rewrite — no GUI)
    ↓
Phase 3 (IntelliJ plugin — depends on clean mars-core)
```

Phases 2 and 3 can overlap: once Phase 1 is complete and `mars-core` has a stable API surface, plugin scaffolding (file type, lexer, syntax highlighting) can begin in parallel with core rewrite work. The plugin just depends on the Phase 1 interfaces, not the final Phase 2 implementations.

The Venus GUI can remain functional throughout Phases 0, 1, and 2 — it is never broken, only superseded.
