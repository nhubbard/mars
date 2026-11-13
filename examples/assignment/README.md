# Grade a MARS assignment

Build the CLI from the repository root with `./mars5/gradlew -p mars5 :cli:jar`.
Then run the example with JDK 25.

```sh
java -jar mars5/cli/build/libs/cli-1.0-SNAPSHOT.jar grade \
  examples/assignment/assignment1.kts examples/assignment/submission.s
```

## Edit assignments in IntelliJ

1. Build the CLI JAR using the command above.
2. Open `examples/assignment` as a Gradle project in IntelliJ IDEA with the Kotlin plugin
   enabled and JDK 25 selected for Gradle. Sync the project so Kotlin can discover the MARS
   script definition in the CLI JAR. You can point a copy of the project at another CLI JAR
   by setting `marsCliJar` to its absolute path in `gradle.properties`.
3. Open `assignment1.kts`. Use names such as `assignment2.kts` and `assignment12.kts` for
   automatic editor discovery. If prompted, load the script configuration. The **MARS
   assignment** definition supplies the `case` receiver for Kotlin completion and type checking.
4. Run the `gradeAssignment` task from the Gradle tool window. Kotlin's generic script launcher
   lacks the submitted sources that the MARS grading command supplies.

From the repository root, the same task is available as
`./mars5/gradlew -p examples/assignment gradeAssignment`.

## Grade submissions

Use the same command in GitHub Classroom with your instructor-owned script and the student's
assembly path. Exit status 0 means all cases passed, 1 means at least one case failed, and 2 means
the command or assignment script needs attention. Each case produces a named result.

Each case assembles the supplied files into a fresh simulator. Use `startAt` to choose a text label,
`setRegister` and `setWord` to set initial inputs, and `requireTextLabel` and `requireDataLabel` to
check the expected structure. An exported label takes precedence over private labels. Ambiguous
private labels cause a case to fail. Word offsets are in bytes and must resolve to aligned
addresses. `expectBytes` accepts unsigned byte values and supports unaligned addresses.

`step` counts machine instructions. A pseudo-instruction may assemble to several machine
instructions. `runUntil` pauses before the instruction at the named label and can stop immediately
if the current PC already points there. Check state at each stop with `expectRegister`,
`expectWord`, `expectBytes`, or `expectOutput`. Use `runToExit` to check syscall 10 or 17
termination. The default expected exit code is 0.

Pass `input = "42\\n"` as a case option to provide a console fixture. Set `utf8Text = true`
when the assignment uses UTF-8 string syscalls. `instructionLimit` defaults to 100,000 and applies
across all execution steps in the case. The grader also limits source size, assembly work, and
captured output. It enables console syscalls, heap allocation, and exit. File descriptors, MIDI,
dialogs, the clock, sleep, and random services are disabled for graded programs. Supply every
permitted include with `--include path/to/header.inc` after the assembly entry paths.

For a MARS64 exercise, import `edu.missouristate.mars.api.MarsTarget` and pass
`target = MarsTarget.MARS64` to `case`. Use Kotlin `Long` values with `setRegister` and
`expectRegister` to test all 64 bits.

Keep assignment scripts in the instructor-controlled repository or fetch them from a trusted
revision. A Kotlin assignment script runs with the privileges of the CLI process.
