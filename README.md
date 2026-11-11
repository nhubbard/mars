# MARS 5

MARS 5 combines an independent Java assembler/simulator with a Kotlin IntelliJ plugin. Phase 2.5 has removed
the deprecated global runtime and compatibility module. The independent core and CLI run without
Venus, and the standalone desktop application now uses the same session-owned core.

The Kotlin IntelliJ plugin provides MARS assembly editing, build diagnostics, native Run and Debug,
and educational tools. The core, CLI, and desktop application are Java. See the
[IntelliJ component mapping](INTELLIJ_COMPONENT_MAPPING.md) for scope and release gates,
the [plugin implementation notes](PLUGIN_IMPLEMENTATION.md) for usage and verification,
and the [modernization plan](MODERNIZATION_PLAN.md) for the broader project.

| Module | Responsibility |
| --- | --- |
| `mars5/core` | Independent assembler, simulator, hardware, undo, and frontend contracts |
| `mars5/cli` | Standalone command-line launcher |
| `mars5/datapath` | Compiled MIPS X-Ray diagram tables shared by both frontends |
| `mars5/desktop` | Existing Venus editor and tools with explicit session ownership |
| `mars5/plugin` | Kotlin IntelliJ plugin for editing, assembling, running, and debugging MIPS |

Build with JDK 25 and the Gradle wrapper:

```sh
cd mars5
./gradlew build :plugin:buildPlugin :core:benchmarkClasses
java -jar cli/build/libs/cli-1.0-SNAPSHOT.jar --help
java -jar cli/build/libs/cli-1.0-SNAPSHOT.jar path/to/program.s
java -jar desktop/build/libs/desktop-1.0-SNAPSHOT.jar
```

The plugin targets IntelliJ IDEA 2026.3 (build 263) using Kotlin 2.4.21 and IntelliJ Platform
Gradle Plugin 2.20.0. It uses the IDE's Kotlin runtime. Its ZIP is produced in
`mars5/plugin/build/distributions`. To use an installed IDE instead of downloading the pinned SDK,
pass `-PlocalIdePath="/path/to/IntelliJ IDEA.app"`.

## Try MARS in IntelliJ

1. Build the ZIP with `./gradlew :plugin:buildPlugin` from `mars5`. In IntelliJ 2026.3, open
   **Settings → Plugins** and choose **Install Plugin from Disk** from the gear menu.
2. Open [the debug-sort example directory](examples/debug-sort) as a project. Open
   `sort-and-search.asm`, right-click in the editor, and run it. The included `console.s`
   supplies its print macros. Enter `7` in the Run console to see the sorted values and
   the first match at index `4`.
3. Set a breakpoint on `search_probe`, start the same program in Debug, and enter `7` again.
   Use **Step Over** to follow the search, and inspect registers and memory in the Debug tool window.
   Open **View → Tool Windows → MARS Tools** to inspect the selected execution.

For a clean development launch from `mars5`, run `./gradlew clean cleanSandbox runIde`.

## Command-line usage

The CLI supports ordered sources, project-directory assembly, and program arguments after `--`.
Run options include memory layouts, branch delay slots, self-modifying code, and step limits.
Automated builds can set separate exit codes for assembly and simulation errors.
Use `--dump REGION FORMAT FILE` to export `.text`, `.data`, or an inclusive word address range.
The CLI lists the available formats in `--help`. Repeat `--dump` to write several files from one run.
Use `--register NAME` and `--memory-range M-N` to print the machine state after execution.
The `hex`, `dec`, and `ascii` options select the format. `me` directs reports to stderr.
Use `--instruction-count` (`ic`) to report completed instructions, including a normal exit syscall.
Use `--trace-phases` (`d`) to see the tokenization, assembly, and simulation phases. `ad` and `da`
trace an assembly-only invocation. Use `me` to keep trace output separate from program stdout.
For embedding, use `MarsComponent.assembler()`, `simulator()`, `memory()`, and `registerFile()`;
close the owning `MarsContext` when finished. The core has no desktop or IntelliJ dependency.

The original project description follows.

MARS (official) MIPS Assembler and Runtime Simulator

 MARS is a lightweight interactive development environment (IDE) for programming in MIPS assembly language, intended for educational-level use with Patterson and Hennessy's Computer Organization and Design.

 It is available for you to download as an executable JAR file.  Click the MARS v.4.5 link at right, under Releases, to access the download.

 We have relocated the MARS website to  https://dpetersanderson.github.io/    There are still a couple of display issues to work out but all website content is there.  This includes all documentation and the JAR download.  We are not experienced in github so if you know a way we can host the website in this MARS repository let us know.

 This is the original MARS, developed by Pete Sanderson and Ken Vollmar.  Developed while Pete was professor at Otterbein University and Ken was professor at Missouri State University.  Both are now retired. This is a legacy application but is very stable and continues to be used by universities throughout the world as of 2024. The last release was MARS 4.5 in August 2014.  
 
 Until October 2024 the MARS website, including executable JAR file download, was hosted by Missouri State University. That is no longer the case. That's why it is here.
