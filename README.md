# MARS 5

MARS 5 is a MIPS assembler and simulator with a standalone CLI and an IntelliJ plugin.
The plugin supports editing, building, running, and debugging MARS assembly and includes
educational tools such as the Bitmap Display and the X-Ray datapath view.

| Module | Responsibility |
| --- | --- |
| `mars5/core` | Independent assembler, simulator, hardware, undo, and frontend contracts |
| `mars5/cli` | Standalone command-line launcher |
| `mars5/datapath` | Compiled MIPS X-Ray diagram tables for the IntelliJ plugin |
| `mars5/plugin` | Kotlin IntelliJ plugin for editing, assembling, running, and debugging MIPS |
| `mars5/plugin-jvm-compiler` | Registers the MARS compiler with IntelliJ's build process |

For IntelliJ development, open the `mars5` directory as a Gradle project. IntelliJ generates its
local project settings in the Git-ignored `.idea` directory.
Copy `mars5/inspection-preserved-prose.xml` and `mars5/inspection-pseudo-op-license.xml`
into `mars5/.idea/scopes/`, then import
`mars5/inspections.xml` under **Settings → Editor → Inspections**. The scope keeps grammar and
style suggestions out of the distributed licenses and historical help pages. It also preserves the
license wording embedded in the pseudo-op catalog. The profile accepts
the Kotlin string forms used for literal MIPS registers and allows public experimental IntelliJ
APIs on the pinned IDE. Internal API and scheduled-removal checks remain enabled.

Build with JDK 25 and the Gradle wrapper:

```sh
cd mars5
./gradlew build :plugin:buildPlugin :core:benchmarkClasses
java -jar cli/build/libs/cli-1.0-SNAPSHOT.jar --help
java -jar cli/build/libs/cli-1.0-SNAPSHOT.jar path/to/program.s
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

## Grade assembly assignments

Use `mars grade assignment1.kts submission.s` to run instructor-authored checks against a
student's assembly. The [assignment example](examples/assignment) includes a Kotlin script,
a sample submission, and an IntelliJ Run configuration. See the
[assignment setup guide](examples/assignment/README.md) for Kotlin completion, grading from
Gradle or GitHub Classroom, and checking script registration.

## Interactive input

In an IntelliJ MARS Run or Debug configuration, set **Standard input** to
**Dialogs** to show a text field for standard input syscalls. You can use the
editor and tool windows while the simulation waits for the dialog. Reads from
file descriptor 0 use the console.

For a program-controlled pause, syscall 55 (`MessageDialog`) shows an **OK**
button. Put the message address in `$a0` and the icon type in `$a1` (`1` for
information). The simulation continues when the user acknowledges the message.

## Tool settings in assembly source

Use **MARS Tools → Bitmap Display → Open Display** to open the selected execution's
bitmap in the editor area. The tool window holds the connection and display settings.
For a 1920 × 1080 framebuffer with one word per pixel, choose the **Default** memory
layout, **Heap** as the base, and **1 × 1** units. Reserve 8,294,400 bytes for the pixels.

A Run or Debug entry file can configure tools in the comment header above its first
assembly statement. Use a tool ID followed by space-separated `key=value` pairs.
Several lines can configure one tool or different tools.

```asm
# mars:tool bitmap width=1920 height=1080 base=heap
# mars:tool bitmap unitWidth=1 unitHeight=1
# mars:tool cache blocks=32 wordsPerBlock=4
```

Settings apply to that execution before its first instruction. The tool window opens
on the first configured tool, and a configured bitmap also opens its editor tab.
A tool ID on its own uses the tool defaults. Invalid settings are reported in the
console and the program can still run.

| Tool ID | Settings |
| --- | --- |
| `bitmap` | `width`, `height`, `unitWidth`, `unitHeight`, `base` |
| `references` | The bitmap settings plus `wordsPerCell` |
| `cache` | `blocks`, `wordsPerBlock`, `placement` (`direct`, `full`, `set_associative`), `ways`, `replacement` (`lru`, `random`), `logging` |
| `branchHistory` | `size`, `historyBits`, `initialTaken` |
| `keyboardDisplay` | `delay` (`fixed`, `uniform`, `normal`), `instructions`, `displayAfterDelay` |
| `marsBot` | `instructionsPerTick` |
| `counter`, `statistics`, `digitalLab`, `scavenger`, `xray` | Connection only |
| `float` | Open the tool |

Use `connected=false` to open a tool with capture or its device disconnected. The
bitmap bases are `global_data`, `global_pointer`, `static_data`, `heap`, `mmio`, and
`wide_data`. Memory Reference Visualization also accepts `text` and `wide_text`.
Installed tool providers can use their own IDs and declare their own settings.

## Instruction set

MARS uses the MIPS64 Release 6 instruction set with MSA vectors for IntelliJ, CLI,
and grading runs. The debugger can inspect, edit, and reverse changes to the full integer registers.
Remove `--target` from older CLI invocations and `target = ...` from assignment cases.
Word instructions such as `addiu` and `sll` use the low 32 bits and write sign-extended word
results. Bitwise operations, comparisons, branches, and register moves use the full 64-bit integer
registers. MARS keeps the classroom memory layouts and provides two sparse 4 MiB regions above 4 GiB.
Place data above 4 GiB with `.data 0x100000000` and executable code with `.text 0x100400000`.
Use `.dword` for aligned 64-bit constants or label addresses and `ld` and `sd` to load and store them.
The assembler, simulator, debugger, dump formats, and grading DSL preserve these addresses.

For MARS64 executions, X-Ray replays a Release 6 instruction path with the full PC,
the 32-bit instruction encoding, and memory accesses from the simulator. Branch History
shows the full encoded destination. Choose **Data above 4 GiB** in Bitmap Display or Memory
Reference Visualization, and **Text above 4 GiB** in Memory Reference Visualization to
observe these regions. The Floating Point tool supports both IEEE 754 precisions and can edit
a MARS64 doubleword register while Debug is paused.

For word multiplication and division, use the three-register `mul`, `mulu`, `div`, and
`divu` forms. They write a sign-extended word result to the destination register.
Use `muh` or `muhu` for the high product word and
`mod` or `modu` for the remainder. MARS64 reports a trap on division by zero.
MARS64 executes architectural delay slots for delayed branches and jumps. Compact control
transfers have no delay slot. If an older program assumed delay slots were disabled, add
explicit slots after delayed transfers or use compact forms where appropriate, such as
`bc`, `balc`, and `jrc $ra`. Ordinary scalar loads and stores support unaligned data in
both byte orders. The `ulw`, `usw`, `ulh`, `ulhu`, `ush`, `uld`, and `usd` aliases use
those instructions. The `addi` pseudo preserves signed overflow trapping and uses `$at`
as a temporary. Put multi-instruction pseudos outside delay slots. The assembler and
IntelliJ give migration guidance for removed forms that require source changes, including
two-operand divides and HI/LO operations. IntelliJ offers a quick fix for branch-likely
instructions with a `nop` delay slot and for `daddi` when the operands rule out overflow.
Review classic uses of `ld` and `sd` that expected
two adjacent 32-bit GPRs. The Release 6 forms transfer one 64-bit GPR.
MARS64 provides 32 individually addressable 64-bit floating-point registers and supports
FCSR rounding and exceptions for scalar arithmetic. MSA vector instructions use 32 128-bit
registers whose low halves share storage with the scalar registers. MARS runs freestanding
source and uses the educational MARS syscall ABI.
See the [Release 6 inventory](docs/mips64-release-6-inventory.md) for instruction coverage,
compiler syntax, and the facilities outside the simulator's scope. The [FIR filter example](examples/mars64)
uses vectors, scalar code, and addresses above 4 GiB in a program you can run, debug, and grade.

## UTF-8 assembly and console text

Use `.utf8 "こんにちは"` to emit UTF-8 bytes, or `.utf8z "こんにちは"` to add a zero byte
after each string. Both directives are available in every project. To also encode `.ascii` and
`.asciiz` as UTF-8 and use UTF-8 in console string syscalls 4 and 8, pass `--utf8-text` to the
CLI or enable **Use UTF-8 for string directives and console string syscalls** under
**Settings → Build, Execution, Deployment → MARS Assembly**. Existing projects keep their
original byte encoding by default.

In UTF-8 mode, syscall 8 interprets `$a1` as a buffer size in bytes, including the final zero.
It writes only whole UTF-8 code points and adds a newline when the entire line and the newline
fit. Use byte loads and stores when examining or copying an encoded string.

The original project description follows.

MARS (official) MIPS Assembler and Runtime Simulator

 MARS is a lightweight interactive development environment (IDE) for programming in MIPS assembly language, intended for educational-level use with Patterson and Hennessy's Computer Organization and Design.

 It is available for you to download as an executable JAR file.  Click the MARS v.4.5 link at right, under Releases, to access the download.

 We have relocated the MARS website to  https://dpetersanderson.github.io/    There are still a couple of display issues to work out but all website content is there.  This includes all documentation and the JAR download.  We are not experienced in github so if you know a way we can host the website in this MARS repository let us know.

 This is the original MARS, developed by Pete Sanderson and Ken Vollmar.  Developed while Pete was professor at Otterbein University and Ken was professor at Missouri State University.  Both are now retired. This is a legacy application but is very stable and continues to be used by universities throughout the world as of 2024. The last release was MARS 4.5 in August 2014.  
 
 Until October 2024 the MARS website, including executable JAR file download, was hosted by Missouri State University. That is no longer the case. That's why it is here.
