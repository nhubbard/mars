# Grade a MARS assignment

## Open and run the example in IntelliJ IDEA

1. With JDK 25, build the CLI from the repository root with
   `./mars5/gradlew -p mars5 :cli:jar`.
2. Open **the `examples/assignment` directory** as its own Gradle project. Keep the Kotlin
   plugin enabled and use JDK 25 for the project SDK and Gradle JVM. Sync Gradle to set up
   the grading task. For a copy of the example, set `marsCliJar`
   in `gradle.properties` to the absolute path to your built CLI JAR.
3. Open [`assignment1.kts`](assignment1.kts) and load the script configuration if prompted.
   The **MARS assignment** Kotlin script definition supplies completion, navigation, and type
   checking for `case` and its assertions. Assignment scripts can use any ordinary `.kts`
   filename, such as `linked-list.kts`. The `.gradle.kts` and `.main.kts` suffixes keep their
   dedicated Kotlin definitions.
4. Select **Grade MARS assignment** in the Run menu. The shared configuration runs
   `gradeAssignment` with `assignment1.kts` and `submission.s`. The Kotlin script editor
   handles authoring, and the Gradle configuration supplies the submission to the MARS grader.

The example includes its own Gradle wrapper. Run the same task from `examples/assignment`, or
choose different files with Gradle properties. Relative paths are resolved from that directory.

```sh
./gradlew gradeAssignment
./gradlew gradeAssignment \
  -Passignment=assignment2.kts -Psubmission=/path/to/student.s
```

## Check script registration

With the MARS MIPS and Kotlin plugins enabled, IntelliJ registers **MARS assignment**
automatically, including in projects without Gradle. If you previously added the CLI JAR
manually, you can remove those custom entries from **Settings → Languages & Frameworks →
Kotlin → Kotlin Scripting**. Use **Reload definitions** there and **Reload script
configuration** in the editor if IntelliJ has cached an earlier configuration.

IntelliJ distinguishes a missing JAR marker from a definition that the current project has
yet to load. The CLI build packages the marker and tests that it reaches the finished JAR.
To check the exact JAR your project uses, run the following with its path.

```sh
jar tf mars5/cli/build/libs/cli-1.0-SNAPSHOT.jar |
  grep -Fx 'META-INF/kotlin/script/templates/edu.missouristate.mars.cli.grading.AssignmentScript.classname'
```

Without the MARS plugin, IntelliJ can also discover the definition from a Gradle project
library dependency on the MARS CLI JAR. To configure it manually, add the built CLI JAR
under **Definition classpath** and enter
`edu.missouristate.mars.cli.grading.AssignmentScript` under **Definition classes** in Kotlin
Scripting settings. Rebuild the JAR if you updated MARS since the last build. Then reload
definitions and the script configuration.

## Grade submissions

Run the CLI directly in GitHub Classroom with your instructor-owned script and the student's
assembly path.

```sh
java -jar mars5/cli/build/libs/cli-1.0-SNAPSHOT.jar grade \
  examples/assignment/assignment1.kts examples/assignment/submission.s
```

Exit status 0 means all cases passed, 1 means at least one case failed, and 2 means the command
or assignment script needs attention. Each case produces a named result.

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

Pass `input = "42\n"` as a case option to provide a console fixture. Set `utf8Text = true`
when the assignment uses UTF-8 string syscalls. `instructionLimit` defaults to 100,000 and applies
across all execution steps in the case. The grader also limits source size, assembly work, and
captured output. It enables console syscalls, heap allocation, and exit. File descriptors, MIDI,
dialogs, the clock, sleep, and random services are disabled for graded programs. Supply every
permitted include with `--include path/to/header.inc` after the assembly entry paths.

For a MARS64 exercise, import `edu.missouristate.mars.api.MarsTarget` and pass
`target = MarsTarget.MARS64` to `case`. Use Kotlin `Long` values with `setRegister` and
`expectRegister` to test all 64 bits. Use `expectVector("w0", low = 1L, high = 2L)`
to check both halves of an MSA register, or `expectVectorLane("w0", lane = 1, width = 64, expected = 2L)`
to check a specific lane. Lane zero starts at the least-significant bits of `low`.
Widths of 8, 16, and 32 compare unsigned values. A width of 64 compares raw `Long` bits.

Keep assignment scripts in the instructor-controlled repository or fetch them from a trusted
revision. A Kotlin assignment script runs with the privileges of the CLI process.
