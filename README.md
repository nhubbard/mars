# MARS 5

MARS 5 combines an independent Java assembler/simulator with a Kotlin IntelliJ plugin. Phase 2.5 has removed
the deprecated global runtime and compatibility module. The independent core and CLI run without
Venus, and the standalone desktop application now uses the same session-owned core.

The IntelliJ plugin starter is Kotlin. Core, CLI, and desktop remain Java for now. All new plugin
code and tests use Kotlin. The next work follows [the detailed IntelliJ component mapping](INTELLIJ_COMPONENT_MAPPING.md),
which covers full MARS feature parity, native language/build/Run/Debug integration, all educational
tools, and near-full branch coverage using Jupiter tests. See also [the current plan](MODERNIZATION_PLAN.md)
and [the Phase 2.5 completion record](PHASE_2_5_PLAN.md).

| Module | Responsibility |
| --- | --- |
| `mars5/core` | Independent assembler, simulator, hardware, undo, and frontend contracts |
| `mars5/cli` | Standalone command-line launcher |
| `mars5/desktop` | Existing Venus editor and tools with explicit session ownership |
| `mars5/plugin` | Kotlin IntelliJ plugin; lazy project-scoped core lifecycle |

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
pass `-PlocalIdePath="/path/to/IntelliJ IDEA.app"`. Language support, consoles, and debugging
are future milestones; the starter currently establishes project session ownership and disposal.

The CLI supports ordered sources, project-directory assembly, and program arguments after `--`.
Run options include memory layouts, branch delay slots, self-modifying code, and step limits.
Automated builds can set separate exit codes for assembly and simulation errors.
For embedding, use `MarsComponent.assembler()`, `simulator()`, `memory()`, and `registerFile()`;
close the owning `MarsContext` when finished. The core has no desktop or IntelliJ dependency.

The original project description follows.

MARS (official) MIPS Assembler and Runtime Simulator

 MARS is a lightweight interactive development environment (IDE) for programming in MIPS assembly language, intended for educational-level use with Patterson and Hennessy's Computer Organization and Design.

 It is available for you to download as an executable JAR file.  Click the MARS v.4.5 link at right, under Releases, to access the download.

 We have relocated the MARS website to  https://dpetersanderson.github.io/    There are still a couple of display issues to work out but all website content is there.  This includes all documentation and the JAR download.  We are not experienced in github so if you know a way we can host the website in this MARS repository let us know.

 This is the original MARS, developed by Pete Sanderson and Ken Vollmar.  Developed while Pete was professor at Otterbein University and Ken was professor at Missouri State University.  Both are now retired. This is a legacy application but is very stable and continues to be used by universities throughout the world as of 2024. The last release was MARS 4.5 in August 2014.  
 
 Until October 2024 the MARS website, including executable JAR file download, was hosted by Missouri State University. That is no longer the case. That's why it is here.
