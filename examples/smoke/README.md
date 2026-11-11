# MARS IntelliJ smoke project

The smoke Gradle root links to the MARS checkout as a composite build and uses
its Java 25 core directly. IntelliJ can import the root with the wrapper and
expose the `instruction-lab` subproject. The existing playback scripts and
fixture projects can still be launched independently from `mars5`.

The Gradle files in this directory are also the templates for the sibling
`mars-ide-smoke` folder. To set that folder up on a fresh machine, copy these
two Gradle files and the wrapper files from `mars5` into it, then open the
folder in IntelliJ as a Gradle project. The default expects the MARS checkout
to be its sibling, named `mars-original`. A different checkout can be set
with the `marsRepository` Gradle property.

From the smoke folder, with JDK 25 available:

```sh
./gradlew projects
./gradlew check
./gradlew :instruction-lab:workbench
```

## MIPS Binary Workbench

Open `instruction-lab/src/main/mips/workbench.asm` in the linked module
and use the MARS Run or Debug action. Select the **Default** memory layout.
The program supports both delayed-branch settings. It uses an included
assembly library, nested macros and equivalences, so it also makes a useful
navigation, formatting, breakpoint, and stepping fixture.

- **Inspect** takes a signed decimal word and displays its unsigned,
  hexadecimal, and binary representations, population count, and MIPS fields.
- **CRC-32** computes the reflected CRC-32/ISO-HDLC checksum of an input line
  and displays the number of bytes consumed.
- **Encode** packs validated I-format or R-format fields into a word and
  displays the same breakdown.
- **Rotate** rotates a word right by a selected number of bits before
  displaying it.

For example, choose CRC-32 and enter `123456789` to get `0xcbf43926`. For an
I-format example, enter opcode `9`, rs `9`, rt `8`, and immediate `-1` to
encode `addiu $t0,$t1,-1` as `0x2528ffff`.

## Complete catalog exercise

The same subproject's `exerciseInstructions` task generates and executes
one isolated assembly program per core catalog form. It verifies the
selected basic implementation or the assembler's pseudo-expansion markers,
then records the probe's executed words, emitted words, and exit or expected
exception in `instruction-lab/build/instruction-catalog/coverage.tsv`.
The generated programs under `build/instruction-catalog/sources` can be
opened individually for debugging. Each header specifies its memory layout.
The probes assume delayed branching is off.

A few catalog probes use alternate operand values to fit the simulated
memory map and meet doubleword alignment. The catalog's unsigned
load and store offset examples use `32768` to select the pseudo form.
Expected breakpoint, trap, and overflow cases count only when execution
reaches the probe and reports the expected exception cause. The catalog
check covers instruction forms.
The interactive workbench exercises a subset of the syscall services.
