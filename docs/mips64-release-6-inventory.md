# MIPS64 Release 6 instruction inventory

This inventory starts with the 30 native forms present in the original MARS64
baseline and encoding probes for 24 additional scalar forms. “Encoding checked” means one independently assembled
word per form. The MSA forms are cataloged below. Scalar classification
and execution conformance remain in progress. See the [implementation plan](mips64-source-compatibility.md)
for the intended coverage.

## Reference indexes

- [Scalar section index](instruction-inventory/mips64-scalar-sections.tsv)
  tracks all 307 entries in the Volume II-A section 3.2 table of contents.
- [Scalar operand forms](instruction-inventory/mips64-scalar-forms.tsv)
  lists concrete forms, including 115 tagged as removed and 44 scalar comparison forms.
  Generic condition notation, untagged aliases, and privilege and operand
  constraints still need review against the instruction descriptions.
  The table of contents and instruction descriptions use different page
  numbers in this copy. The operand table uses the printed description pages.
- [MSA forms](instruction-inventory/mips64-msa-forms.tsv) lists all 531
  concrete forms in the SIMD reference's Format blocks, including the
  scalar LSA and DLSA cross-references and four MIPS64-specific vector forms.
  The table records the documented operands, availability, and reference
  page. The MARS64 catalog contains all 529 vector and control forms and
  the two scalar cross-references. The low 64 bits of each vector register share
  storage with the corresponding scalar FPR. Scalar writes preserve the
  other bits where the ISA leaves them unpredictable. Backstep restores
  both halves of a vector write. Vector loads and stores support all four
  element widths, either byte order, and unaligned addresses in ordinary
  storage within the current memory map. Integer vector arithmetic,
  multiply-accumulate, comparisons, shifts, and bit counts execute at all
  four element widths. Integer vector division and remainder return zero
  for lanes with a zero divisor, a deterministic choice for the result the
  ISA leaves unpredictable. See the MSA forms table for individual status.

## Current MARS64 forms

All 30 example words below are in the
[encoding fixture](../mars5/core/src/test/resources/mars64-basic-encoding-vectors.tsv).
They were generated with LLVM 23.1.5 for big-endian MIPS64 Release 6.
The Jupiter test checks that MARS64 assembles each word, recovers the operand
fields on decode, and that the same raw word has no MARS32 decoding. The current decoder represents negative signed immediates as raw
16-bit fields for these wide forms.

| Current native forms | Release 6 encoding evidence | Execution work |
| --- | --- | --- |
| `dadd`, `daddu`, `dsub`, `dsubu`, `daddiu` | Checked | Confirm signed overflow, wrap, immediates, zero destination, and backstep. |
| `dmul`, `dmuh`, `dmulu`, `dmuhu`, `ddiv`, `ddivu`, `dmod`, `dmodu` | Checked, Appendix A.4 Table A.26 | Review full-width signed and unsigned edge cases. MARS currently traps on a zero divisor. |
| `dsll`, `dsll32`, `dsrl`, `dsrl32`, `dsra`, `dsra32`, `dsllv`, `dsrlv`, `dsrav` | Checked | Confirm shift boundaries and that variable counts use six bits. |
| `drotr`, `drotr32`, `drotrv`, `dclz`, `dclo` | Checked | Confirm masked counts and all-zero/all-one results. |
| `lwu`, `ld`, `sd` | Checked | Ordinary storage supports misaligned accesses in both byte orders within the current 32-bit address map. |

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
its previous value. The program counter and mapped segments still use
32-bit addresses.

`mfc0` sign-extends its word result. `mtc0` preserves the full source value
when writing an educational address register. For both forms, reserved or
unimplemented register and selector combinations read as zero or ignore the
write. IntelliJ uses CP0 register names when completing the register operand
of the word and doubleword transfers.

`eret` and `eretnc` return through the educational EPC register. `eret`
clears the load-linked reservation and `eretnc` preserves it. A fault in a
MARS64 delay slot records the branch address in EPC and sets `Cause.BD`.
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

## Shared forms and encoding conflicts to audit next

- **Integer multiply and divide.** MARS64 uses the register-result word forms
  in Appendix A.4 Table A.26 and excludes the legacy HI/LO native forms.
  MARS32 retains its existing behavior. MARS64 defines a trap on division
  by zero, and the inventory still tracks source migration guidance for
  removed forms.
- **Compact control flow.** Expand each operand constraint in Table A.29.
  Check overlapping encodings using raw words, then audit link values, taken
  and untaken paths, and forbidden-slot behavior. MARS64 now assigns the
  `addi` primary opcode to `beqc`, `beqzalc`, and `bovc`. The same words
  still decode as `addi` in MARS32.
  `bgtc`, `blec`, `bgtuc`, and `bleuc` are assembler aliases. Each emits one
  native compact branch with its register operands reversed.
- **Delayed control flow.** MARS64 executes the architectural delay slot for
  `beq`, `bne`, `bgez`, `bgtz`, `blez`, `bltz`, `j`, and `jal`. An untaken
  branch still reserves its delay slot. `jal` links to the second instruction
  after the jump. These rules are independent of the MARS32 delay setting.
- **PC-relative and memory operations.** Audit the entire family in
  Tables A.27 and A.28, plus word `ll`/`sc` and the Release 6 encodings
  for doubleword reservations. Ordinary scalar halfword, word, and
  doubleword loads and stores may cross word boundaries. Linked and
  conditional accesses still require natural alignment. The address map
  remains 32-bit. The `ulw`, `usw`, `ulh`, `ulhu`, and `ush` pseudos use
  the corresponding ordinary memory instruction on MARS64. For a legacy
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
- **Remaining scalar and optional families.** Expand Volume II-A by operand
  form and classify shared integer, COP1, memory, system, and optional forms.
  Track removed and privileged forms separately from missing user-mode
  instructions. Inventory MSA from its own reference, including every
  supported lane width, control-state effect, and scalar register alias.

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
