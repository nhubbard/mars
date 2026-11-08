# MARS

The modernization work has completed the core rewrite and stops before the IntelliJ plugin phase.
The independent assembler and simulator can run without Venus. The historical project description
below is retained; see [the completion report](PHASE_2_COMPLETION.md) and
[the modernization plan](MODERNIZATION_PLAN.md) for the current architecture.

Build with JDK 25:

```sh
cd mars5
./gradlew build :core:benchmarkClasses
java -jar cli/build/libs/cli-1.0-SNAPSHOT.jar --help
java -jar cli/build/libs/cli-1.0-SNAPSHOT.jar path/to/program.s
```

The CLI supports multiple sources, program arguments after `--`, assembly-only mode, memory layouts,
branch delay slots, start-at-main, pseudo-instruction policy, warnings-as-errors, and step limits.
Use the `core` module for embedding; `MarsComponent.assembler()`, `simulator()`, `memory()`, and
`registerFile()` expose the frontend contracts. Close the owning `MarsContext` when finished.
The `plugin` jar currently packages the frozen Venus frontend and its separate legacy adapters;
The next step is [Phase 2.5 legacy-deprecation cleanup](PHASE_2_5_PLAN.md), followed by IntelliJ
Platform integration in Phase 3.

MARS (official) MIPS Assembler and Runtime Simulator

 MARS is a lightweight interactive development environment (IDE) for programming in MIPS assembly language, intended for educational-level use with Patterson and Hennessy's Computer Organization and Design.

 It is available for you to download as an executable JAR file.  Click the MARS v.4.5 link at right, under Releases, to access the download.

 We have relocated the MARS website to  https://dpetersanderson.github.io/    There are still a couple of display issues to work out but all website content is there.  This includes all documentation and the JAR download.  We are not experienced in github so if you know a way we can host the website in this MARS repository let us know.

 This is the original MARS, developed by Pete Sanderson and Ken Vollmar.  Developed while Pete was professor at Otterbein University and Ken was professor at Missouri State University.  Both are now retired. This is a legacy application but is very stable and continues to be used by universities throughout the world as of 2024. The last release was MARS 4.5 in August 2014.  
 
 Until October 2024 the MARS website, including executable JAR file download, was hosted by Missouri State University. That is no longer the case. That's why it is here.
