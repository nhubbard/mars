# First MARS book examples

Use these programs with [Observing a MARS program](../../docs/book/01-observing-a-program.md).
Open this directory as an IntelliJ project with the MARS plugin enabled. Run or
Debug one `.asm` file at a time. Each program is a separate entry point.

| Source | Expected observation |
| --- | --- |
| `01-console.asm` | Input `20` produces `41`; `0` produces `1`; `-3` produces `-5` |
| `02-memory.asm` | The word at `result` changes from `0` to `42`; `$t3` receives `42` |
| `03-width.asm` | `$t1` becomes `0xffffffff80000000`; `$t2` becomes `0x0000000080000000` |

## Run the checks

Use JDK 25. Build the CLI from the repository root:

```sh
./mars5/gradlew -p mars5 :cli:jar
```

Then run each script against its matching source, also from the repository root:

```sh
java -jar mars5/cli/build/libs/cli-1.0-SNAPSHOT.jar grade \
  examples/book/01-console-check.kts examples/book/01-console.asm
java -jar mars5/cli/build/libs/cli-1.0-SNAPSHOT.jar grade \
  examples/book/02-memory-check.kts examples/book/02-memory.asm
java -jar mars5/cli/build/libs/cli-1.0-SNAPSHOT.jar grade \
  examples/book/03-width-check.kts examples/book/03-width.asm
```

The six cases check the original programs, including intermediate instruction
states. Restore the source after trying the lesson's modifications before
running these checks. The grader supplies console input automatically and
compares program output without any interactive terminal echo of the input.
