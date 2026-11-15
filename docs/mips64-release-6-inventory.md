# MIPS64 Release 6 instruction inventory

The tables below record which Release 6 forms MARS64 implements and which
depend on hardware outside the simulator. An encoding check compares one
example word per form with LLVM or the instruction reference. Execution tests
are separate and exercise instruction families, operand edges, and state changes.
See the [implementation plan](mips64-source-compatibility.md) for the intended coverage.

## Reference indexes

- [Scalar section index](instruction-inventory/mips64-scalar-sections.tsv)
  tracks all 307 entries in the Volume II-A section 3.2 table of contents.
- [Scalar operand forms](instruction-inventory/mips64-scalar-forms.tsv)
  lists 331 supported forms, including aliases and educational subsets, 119
  removed forms, and 53 forms that need unavailable hardware. A Jupiter test
  checks all 331 example encodings against LLVM 23.1.5 or the reference
  diagrams for compare conditions and paired atomics LLVM omits.
  The table of contents and instruction descriptions use different page
  numbers in this copy. The operand table uses the printed description pages.
- [MSA forms](instruction-inventory/mips64-msa-forms.tsv) lists all 531
  concrete forms in the SIMD reference's Format blocks, including the
  scalar LSA and DLSA cross-references and four MIPS64-specific vector forms.
  The table records the documented operands, availability, and reference
  page. A Jupiter test assembles each of the 531 rows, compares its word with
  LLVM 23.1.5, and checks that the MARS64 decoder identifies the form. The low
  64 bits of each vector register share
  storage with the corresponding scalar FPR. Scalar writes preserve the
  other bits where the ISA leaves them unpredictable. Backstep restores
  both halves of a vector write. Vector loads and stores support all four
  element widths, either byte order, and unaligned addresses in ordinary
  storage, including the MARS64 data region above 4 GiB. Integer vector arithmetic,
  multiply-accumulate, comparisons, shifts, and bit counts execute at all
  four element widths. Integer vector division and remainder return zero
  for lanes with a zero divisor, a deterministic choice for the result the
  ISA leaves unpredictable. See the MSA forms table for individual status.

## Independent execution checks

The [execution fixtures](../mars5/core/src/test/resources/qemu-mips64) run the
same small assembly sequences in QEMU I6400 and MARS64. The harness initializes
memory with fixed bytes, runs the sequence, and compares the output byte for
byte in both byte orders. The output comparison includes guard bytes around stores.

| Probe | Execution checked |
| --- | --- |
| Scalar integer | Word and doubleword arithmetic, all low and high multiply forms, signed and unsigned division and remainder, immediate construction, comparisons, and full-width register selection. |
| Scalar bit operations | Byte and bit swaps, sign extension, leading-bit counts, shifts, rotates, and bitfield extraction and insertion at word and doubleword limits. Includes every CRC-32 and CRC-32C form. |
| Scalar addressing | Signed and unsigned loads, unaligned transfers, and all six PC-relative forms. PC-relative probes check displacement limits, signed and unsigned reads of the same text word, and an aligned doubleword base for both instruction alignments. |
| Branches and jumps | Taken and untaken signed, unsigned, zero, overflow, and FPR-bit comparisons. Delayed and compact calls check return addresses, alternate link registers, and call and return slots. |
| Atomics | Successful word and doubleword linked loads and conditional stores. |
| Exceptions | Overflow, misaligned linked load, breakpoints, and illegal control transfers in delay and forbidden slots. Misaligned jump targets check that the slot and link take effect before the instruction fetch faults. A shared handler records the cause, EPC offset, exception level, destination register, and bad-address offset for address errors. |
| Scalar floating point | Directed rounding, sticky flags, classification, and compare masks. |
| Memory boundaries | Scalar and vector transfers across a mapped 4 KiB boundary. Separate default-layout stack tests cover the last writable byte and verify that crossing the stack end raises an address error before any write. |
| MSA memory | Loads and stores in all four element widths at unaligned addresses, including mixed-width transfers that expose byte and lane ordering. |
| MSA integer | Wrapping arithmetic and accumulation, signed and unsigned saturation, division and remainder, averages, and comparisons. Probes include signed limits and immediate operands in all four lane widths. |
| MSA bit operations | Bit changes and inserts, leading zero and one counts, population counts, exact and rounded shifts, and saturation to a selected bit width. Shift counts include zero, the largest count, and a count that wraps. |
| MSA permutations | Packs, interleaves, shuffles, byte slides, and broadcasts across every applicable lane width. Probes include wrapped selectors, shuffle zeroing bits, and destinations that also supply an input. |
| MSA register transfers and logic | Signed and unsigned extraction into CPU registers, insertion into the upper and lower halves of a vector, whole-vector moves, logical operations, and masks that merge with the previous destination. |
| MSA branches | Whole-vector and per-lane zero tests in all four lane widths, with delay-slot execution on both taken and untaken branches. |
| MSA fixed point and widening | Q15 and Q31 multiplication and accumulation, including saturation and half-step rounding. Signed and unsigned horizontal arithmetic, dot products, and dot-product accumulation widening into halfwords, words, and doublewords. |
| MSA floating point | Both precisions of arithmetic, fused accumulation, classification, extrema, and every quiet and signaling compare predicate. Probes include directed overflow, gradual underflow, flush-to-zero, and a trap that preserves the destination vector and sticky flags. |
| MSA conversion | Rounding to integral values, signed and unsigned integer conversions, half/single/double interchange, Q15 and Q31 quantization, and widening from both halves of a vector. |

The QEMU probes exercise selected inputs for each of the 531 forms in the MSA
reference inventory, including the scalar `lsa` and `dlsa` forms.

The reciprocal probes use powers of two and disregard the Inexact cause and
sticky flag for `frcp` and `frsqrt` on those inputs. The MSA manual allows
approximate implementations to set Inexact there. The core suite separately
checks inexact inputs and the one- and two-ULP accuracy bounds.

The `flog2` reference probes use powers of two. The core suite checks the IEEE
754-2008 §5.3.3 `logB` results required by MSA for nonpowers of two, negative
values, zero, and subnormals. QEMU I6400 can set Inexact for positive finite
nonpowers of two and returns Invalid for negative finite inputs.
[MIPS64 SIMD Architecture manual](https://s3-eu-west-1.amazonaws.com/downloads-mips/documents/MD00868-1D-MSA64-AFP-01.12.pdf)
· [IEEE 754-2008](https://pub.sergev.org/doc/ieee754-2008.pdf)

The checked-in results record the QEMU, LLVM, and LLD versions used to generate
them. The normal Jupiter suite reads the files and requires only the MARS build.
To reproduce the reference results, put `qemu-system-mips64`,
`qemu-system-mips64el`, `llvm-mc`, `llvm-nm`, and `ld.lld` on the path and run
the following from the repository root.

```sh
python3 mars5/core/tools/refresh-qemu-execution-fixtures.py
```

The script prints the results for review. Pass `--write` to update the fixture
after all cases succeed. It runs trusted local probes in QEMU and reads memory
over the debugger’s standard input and output. In MARS, the tests assemble the
instruction sequence as ordinary source. The ELF and startup code belong to the
reference harness. The harness enables QEMU kernel extended addressing so that
PC-relative arithmetic uses the entire 64-bit address.

When a result differs, check the Release 6 or MSA specification before changing
the simulator. Record which cases have defined results and which rely on a
documented MARS teaching behavior. Validate the supported program model,
including integer, floating-point, vector, and memory effects. A board, an OS,
and hardware timing are outside this model.
[QEMU MIPS64 CPU models](https://www.qemu.org/docs/master/system/qemu-cpu-models.html)
· [QEMU debugger interface](https://www.qemu.org/docs/master/system/gdb.html)

## Original MARS64 encoding baseline

All 30 example words below are in the
[encoding fixture](../mars5/core/src/test/resources/mars64-basic-encoding-vectors.tsv).
They were generated with LLVM 23.1.5 for big-endian MIPS64 Release 6.
The Jupiter test checks that MARS64 assembles each word and recovers the operand
fields on decode. The decoder represents negative signed immediates as raw
16-bit fields for these wide forms.

| Original native forms | Release 6 encoding evidence | Behavior |
| --- | --- | --- |
| `dadd`, `daddu`, `dsub`, `dsubu`, `daddiu` | Checked | Doubleword results and full register restoration on backstep. |
| `dmul`, `dmuh`, `dmulu`, `dmuhu`, `ddiv`, `ddivu`, `dmod`, `dmodu` | Checked, Appendix A.4 Table A.26 | Signed and unsigned register results. Division by zero traps. |
| `dsll`, `dsll32`, `dsrl`, `dsrl32`, `dsra`, `dsra32`, `dsllv`, `dsrlv`, `dsrav` | Checked | Variable counts use the low six bits. |
| `drotr`, `drotr32`, `drotrv`, `dclz`, `dclo` | Checked | Doubleword rotations and leading-bit counts. |
| `lwu`, `ld`, `sd` | Checked | Ordinary storage supports misaligned accesses in both byte orders, including above 4 GiB. |

## Scalar forms that depend on hardware

The scalar table marks the dependencies separately from implementation status.
The distinction helps identify which source needs a diagnostic from MARS64.

| Status | Dependency |
| --- | --- |
| `requires-cop2` | A platform-specific coprocessor supplies the conditions, registers, or operations. The manual defines transfer encodings and leaves their device behavior implementation-dependent. |
| `requires-eva` | Enhanced Virtual Addressing uses CP0 access modes and translated user memory from privileged code. |
| `requires-cp0-mvh` | High-half transfers depend on the optional `Config5.MVH` feature and its extended CP0 register definitions. Use `dmfc0` and `dmtc0` to access the educational address registers as doublewords. |
| `requires-privileged-platform` | Global invalidation and TLB forms require privileged address translation. `cache` requires cache state. `rdpgpr` and `wrpgpr` require shadow register sets. `wait` depends on interrupt and power-management behavior. |
| `requires-ejtag` | `deret` and `sdbbp` use processor Debug Mode and its exception state. |

These categories apply to the forms that depend on those facilities. The
existing educational CP0 exception state and the IDE debugger are available
independently. The MARS64 assembler identifies the required hardware facility
when it encounters any of these mnemonics.

`dmfc0` and `dmtc0` cover the four educational CP0 registers with selector
zero. `BadVAddr` and `EPC` retain doublewords on transfer. `Status` and
`Cause` use the low word on write and zero-extend on a doubleword read.
Unsupported register and selector combinations trap. An address fault now
preserves the full effective address in `BadVAddr`, and backstep restores
its previous value. MARS64 register-based integer, floating-point, vector,
and linked memory operations can also access a sparse 4 MiB data region at
`0x0000000100000000`. The region starts zeroed. Accesses that cross its
limit raise an address exception before writing any bytes. Its contents
participate in assembly images and backstep. With self-modifying code
enabled, MARS64 can fetch generated instructions in that region. The program
counter, control transfers, PC-relative instructions, EPC, and backstep
preserve the full address during execution. MARS64 also accepts
`.data 0x100000000` and `.text 0x100400000` for explicit placement in its
high data and text regions. High text runs with the default execution
permissions. `la` and `dla` can load a full-width label address,
`dli` loads a full-width constant, and `.dword label` stores a full-width
address, including forward references. Scalar loads and stores also accept
high labels and full-width numeric addresses directly. These forms use
`$at` to build the address and diagnose a base or store source that
would be overwritten by the expansion. The assembler's source map
preserves these addresses. IntelliJ source and address breakpoints,
stepping, disassembly, and the memory browser use the full address.
The memory browser can edit high data words, and watchpoints can stop on high
data accesses. Explicit CLI and IntelliJ memory dump ranges accept high
addresses. Segment listings retain those addresses, and Intel HEX uses
offsets from the beginning of the requested range.
The grading DSL can start or stop at a high label and read or write high
data. MARS syscall arguments that are pointers to strings or buffers
preserve the full register value. The syscall numbers and numeric
arguments retain the educational MARS ABI.

## Freestanding compiler output

The assembler accepts the metadata produced by Clang for an ordinary
freestanding function. `.section` maps `.text` and its subsections to
user text, and `.data`, `.rodata`, `.rdata`, `.bss`, `.sdata`, `.sbss`, and
their subsections to user data. Recognized metadata section declarations
and directives such as `.type`, `.size`, and `.frame` emit no bytes. The
assembler preserves source order and implements the Release 6 NaN encoding.
It warns when `.set noat` or `.set nomacro` asks for assembler restrictions
it cannot enforce. It rejects sections such as `.got` that depend on a
linker or loader.

`.p2align` accepts a power-of-two exponent, and `.balign` accepts a positive
byte boundary. Both accept an optional fill byte in data and emit whole
`nop` instructions in text. Neither crosses the current segment's bounds.
The assembler also accepts `.global`, `.2byte`, `.short`, `.4byte`, `.long`,
`.8byte`, `.quad`, `.asciz`, and `.string`.

The `%lo`, `%hi`, `%higher`, and `%highest` operators resolve a label and an
optional integer addend into a 16-bit machine field. They can be used with
an immediate or a base-register displacement in a native instruction.
Position-independent relocations that require a global offset table are
outside the classroom address model. [LLVM MIPS fixups](https://www.llvm.org/doxygen/namespacellvm_1_1jitlink_1_1mips.html)

Instruction operands and address relocations can refer to repeatable decimal
labels. For example, `7f` resolves to the next `7:` and `7b` resolves to the
previous `7:` in the same assembly source. Includes and macro expansions
participate in the resulting source order.

The test suite assembles the checked-in Clang 23.1.5 output and calls the
rotate routine twice with a MARS64 assembly caller. It also compares a second
Clang function's linked words with `ld.lld` and executes the function with
static data above 4 GiB. Rebuild the fixtures from their
source in the [compiler fixture directory](../mars5/core/src/test/resources/compiler)
with the following command.

```sh
clang -target mips64-unknown-elf -march=mips64r6 -mabi=64 -mno-abicalls \
  -fno-pic -ffreestanding -fno-asynchronous-unwind-tables -O2 \
  -S rotate-sum.c -o rotate-sum.s
```

Substitute `lookup.c` and `lookup.s` to rebuild the static-data example.

`mfc0` sign-extends its word result. `mtc0` preserves the full source value
when writing an educational address register. For both forms, reserved or
unimplemented register and selector combinations read as zero or ignore the
write. IntelliJ uses CP0 register names when completing the register operand
of the word and doubleword transfers.

`eret` and `eretnc` return through the educational EPC register. `eret`
clears the load-linked reservation and `eretnc` preserves it. A fault in a
MARS64 delay slot or an untaken compact branch’s forbidden slot records the
branch address in EPC and sets `Cause.BD`. [Release 6 branch and jump rules](https://s3-eu-west-1.amazonaws.com/downloads-mips/documents/MD00083-2B-MIPS64INT-AFP-06.01.pdf)
When exception level is already set, a subsequent fault preserves EPC and
`Cause.BD`. A return to a full-width EPC outside the current map raises an
address fault.

`di` and `ei` control delivery of MARS64 device interrupts through
`Status.IE`. Both can copy the previous, sign-extended Status to a GPR.
The simulator defers delivery at exception level or while disabled and
keeps masked device requests pending until they are enabled or withdrawn.
`dvp` and `evp` act as no-ops on the single-core target. `pause` is also
a no-op in ordinary execution and still follows delay-slot and forbidden-slot
restrictions.

`syscall` accepts an optional unsigned 20-bit code. The assembler and
disassembler preserve that field in the machine word. MARS's educational
syscall services still use the service number in `$v0`.

`break` accepts the one- and two-operand spellings used by LLVM's MIPS
assembler. Each operand is ten bits. To migrate a MARS32 `break code`
while preserving all 20 encoded bits, pass `code >> 10` and
`code & 1023` as the two operands.

## Additional scalar forms with encoding probes

MARS64 assembles and executes all six doubleword bitfield forms, as well as
`dlsa`, `dalign`, `dbitswap`, `dsbh`, `dshd`, `dahi`, `dati`, `daui`, `ldpc`, and `lwupc`.
Plain `dext` and `dins` choose their machine forms from the field size and
position. MARS64 also implements `beqc`, `bovc`, `bnvc`, and six compact conditional
branch-and-link forms. An untaken compact branch makes the next instruction
a forbidden slot for control transfers. Backstep restores that state.
MARS64 uses the Release 6 encodings for `ll`, `sc`, `lld`, and `scd`.
These reserve the addressed bytes in ordinary storage. Overlapping writes
invalidate the reservation, and backstep restores it. MARS64 also supports
`llwp`, `scwp`, `lldp`, and `scdp` using the paired bit in the manual.
These run in the single-core simulator and use ordinary storage. Example
register numbers and immediates below are LLVM assembler input, and the hex values are instruction words in architectural bit order. A branch
or PC-relative immediate in this table is the displacement as written to LLVM.

| Example source | Release 6 word | Family or dependency |
| --- | --- | --- |
| `dext $9,$10,1,8` | `7d493843` | Bit fields |
| `dextm $9,$10,1,40` | `7d493841` | Bit fields |
| `dextu $9,$10,33,8` | `7d493842` | Bit fields |
| `dins $9,$10,1,8` | `7d494047` | Bit fields |
| `dinsm $9,$10,1,40` | `7d494045` | Bit fields |
| `dinsu $9,$10,33,8` | `7d494046` | Bit fields |
| `dlsa $9,$10,$11,2` | `014b4855` | Scaled addressing |
| `dalign $9,$10,$11,2` | `7d4b4aa4` | Byte alignment |
| `dbitswap $9,$10` | `7c0a4824` | Bit permutation |
| `dsbh $9,$10` | `7c0a48a4` | Byte permutation |
| `dshd $9,$10` | `7c0a4964` | Halfword permutation |
| `dahi $9,$9,1` | `05260001` | Immediate construction |
| `dati $9,$9,1` | `053e0001` | Immediate construction |
| `daui $9,$10,1` | `75490001` | Immediate construction |
| `ldpc $9,8` | `ed380001` | PC-relative doubleword load |
| `lwupc $9,4` | `ed300001` | PC-relative unsigned word load |
| `lld $9,8($10)` | `7d490437` | Load reservation |
| `scd $9,8($10)` | `7d490427` | Conditional store |
| `dmfc1 $9,$f10` | `44295000` | Scalar FPR transfer |
| `dmtc1 $9,$f10` | `44a95000` | Scalar FPR transfer |
| `beqc $9,$10,4` | `212a0001` | Compact branch, opcode reused from ADDI |
| `bovc $9,$10,4` | `21490001` | Compact overflow branch, opcode reused from ADDI |
| `bnvc $9,$10,4` | `61490001` | Compact overflow branch, opcode reused from DADDI |

## Release 6 behavior and compatibility details

- **Integer multiply and divide.** MARS64 uses the register-result word forms
  in Appendix A.4 Table A.26 and excludes the legacy HI/LO native forms.
  MARS32 retains its existing behavior. MARS64 defines a trap on division
  by zero. The old two-operand divide spellings direct users to explicit
  quotient or remainder destination registers.
- **Compact control flow.** Tests cover overlapping encodings, link values,
  both branch paths, and forbidden-slot behavior. MARS64 assigns the
  `addi` primary opcode to `beqc`, `beqzalc`, and `bovc`. The same words
  still decode as `addi` in MARS32. The MARS64 `addi` pseudo loads the immediate
  into `$at` and uses native `add` to preserve signed overflow trapping.
  `bgtc`, `blec`, `bgtuc`, and `bleuc` are assembler aliases. Each emits one
  native compact branch with its register operands reversed.
  `jrc` and `jalrc` emit `jic` and `jialc` with a zero offset.
- **Doubleword shifts and rotates.** The unsuffixed `dsll`, `dsrl`, `dsra`,
  and `drotr` source forms accept counts 0–63 and select the matching encoding.
  Their explicit `32` forms take the encoded count 0–31.
- **Delayed control flow.** MARS64 executes the architectural delay slot for
  `beq`, `bne`, `bgez`, `bgtz`, `blez`, `bltz`, `j`, and `jal`. An untaken
  branch still reserves its delay slot. `jal` links to the second instruction
  after the jump. These rules are independent of the MARS32 delay setting.
- **PC-relative and memory operations.** The inventory covers Tables A.27
  and A.28, including the Release 6 word and doubleword reservation encodings.
  Ordinary scalar halfword, word, and
  doubleword loads and stores may cross word boundaries. Linked and
  conditional accesses still require natural alignment. Both can use the
  mapped MARS64 data region above 4 GiB. The `ulw`, `usw`, `ulh`, `ulhu`, `ush`, `uld`,
  and `usd` pseudos use the corresponding ordinary memory instruction on MARS64. For a legacy
  left/right pair that loads or stores a complete unaligned word, use
  `lw` or `sw` at the address of the first byte. Code that uses a single
  merge instruction to preserve selected bytes needs an explicit sequence.
  `pref` accepts the Release 6 signed 9-bit displacement. Hints 0–23 do
  nothing in the synchronous memory model, and hints 24–31 raise a
  reserved-instruction exception. It does not access the supplied address.
- **Scalar FPR layout.** MARS64 uses one 64-bit register per scalar FPR,
  including odd-numbered registers. Double-precision operations, doubleword
  loads and stores, and `dmfc1`/`dmtc1` use the full register. `mfc1` and
  `mfhc1` sign-extend their selected word into the destination GPR. The
  simulator preserves the upper word on a word-sized FPR write, giving a
  deterministic result where the ISA leaves it unpredictable. MARS32 keeps
  its even/odd register pairs. The MARS32 `mfc1.d` and `mtc1.d` pseudos
  transfer between two GPRs and two FPRs and are excluded from MARS64.
- **Scalar FPU branches.** MARS64 uses `bc1eqz` and `bc1nez` in place of
  the removed `bc1f` and `bc1t`. Both Release 6 forms test bit 0 and execute
  one delay slot on both paths, independently of the legacy delay-slot setting.
  Their signed displacement is relative to the instruction after the branch.
- **Scalar FPU comparison and control.** MARS64 supports all 22 Release 6
  `cmp` conditions in single and double precision. They write truth masks,
  recognize ordered and unordered inputs, and report invalid comparisons
  through the FCSR. `cfc1` and `ctc1` expose FCSR and its exception and
  enable aliases. FIR is read-only and FCCR is reserved. The fixed-point
  conversions `cvt`, `round`, `trunc`, `ceil`, and `floor` support both
  result widths, with FCSR-selected or instruction-selected rounding and
  invalid and inexact exceptions. `rint.s` and `rint.d` round to integral
  values without changing their format. Conversions to single and double precision use the
  FCSR rounding mode and report conversion exceptions. Scalar `add`, `sub`,
  `mul`, `div`, `maddf`, and `msubf` in both precisions round using FCSR and
  report arithmetic exceptions. Tests check directed rounding, sticky flags,
  and an enabled exception that preserves the destination. `sqrt` and `recip`
  honor FCSR rounding. `rsqrt` approximates the result using nearest-even
  square root and division. All three report exceptions. The minimum and
  maximum instructions report signaling NaNs through FCSR. With invalid
  masked, they prefer a number to a NaN and preserve the first operand's
  payload and signaling bit when both operands are NaNs.
  [MIPS64 FPU programming model](https://s3-eu-west-1.amazonaws.com/downloads-mips/documents/MD00083-2B-MIPS64INT-AFP-06.01.pdf)
- **REGIMM.** MARS64 supports `bal` and `nal` at the retained zero-source
  encodings. Both link past the delay slot. The old register-conditional
  `bgezal` and `bltzal` forms are removed. `sigrie` signals a reserved
  instruction exception and carries a 16-bit software code.
  The six removed immediate traps (`teqi`, `tnei`, `tgei`, `tgeiu`, `tlti`,
  and `tltiu`) remain as pseudos. Each loads a sign-extended 16-bit
  immediate into the assembler temporary `$at` and uses the corresponding
  native register trap. Use an explicit scratch register if the source
  operand is `$at`, or if the comparison must fit in a single delay slot.
- **CRC.** MARS64 includes all eight optional `crc32*` and `crc32c*` forms.
  They consume the low byte, halfword, word, or doubleword of the message
  register, update the accumulator in place, and sign-extend the result.
  The repeated source operand must name the destination register.
- **Barriers and self-modifying code.** MARS64 recognizes `sync`, `ehb`,
  `synci`, `jr.hb`, and `jalr.hb`. Memory and instruction fetch are
  synchronous in the simulator. The hazard-barrier jumps still execute an
  architectural delay slot. Tests exercise writing and executing a new
  instruction with the existing opt-in setting and restoring it on backstep.
  Ordinary `jr` and `jalr` also use an architectural delay slot in MARS64.
  `jr` uses the Release 6 `jalr` encoding with a zero destination register.
  `ssnop` retains its distinct encoding and acts as `nop`. `rdhwr` exposes
  CPU number zero, synchronization step zero, and available paired LL/SC.
  Hardware counters, the OS thread-local pointer, and other register or
  selector combinations raise a reserved-instruction exception.
## Reproduce a probe

Run LLVM's MIPS assembler with a single source form to get the instruction
bytes. The manual's per-instruction diagrams are authoritative when a tool
uses an alias or accepts an extension.

```sh
llvm-mc -triple=mips64 -mcpu=mips64r6 -show-encoding <<'ASM'
.set noreorder
.set nomacro
beqc $9,$10,4
ASM
```

[MIPS64 Release 6 instruction reference](https://jnastarot.io/arch_index/pdf/MD00087-2B-MIPS64BIS-AFP-6.06.pdf) ·
[MIPS64 SIMD instruction reference](http://iommu.com/datasheets/mips/isa/MIPS64_MSA-MD00868-1D-MSA64-AFP-01.12.pdf)
