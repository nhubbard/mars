# MIPS64 Release 6 instruction inventory

This first pass covers the 30 native forms currently added by MARS64 and 24
missing scalar forms. “Encoding checked” means one independently assembled
word per form. Execution conformance and the complete scalar and MSA
inventories are still open. See the [implementation plan](mips64-source-compatibility.md)
for the intended coverage.

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
| `lwu`, `ld`, `sd` | Checked | Current MARS uses a 32-bit address map and requires alignment. Release 6 needs the documented misaligned access behavior. |

## Missing scalar forms with encoding probes

These rows are inventory entries. The current MARS assembler does not accept
these forms. Register numbers and immediates below are LLVM assembler input,
and the hex values are instruction words in architectural bit order. A branch
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

- **Integer multiply and divide.** Compare the existing word forms with Appendix
  A.4 Table A.26. Release 6 reuses names such as `div` for three-register
  forms, while the older two-register operations write HI/LO. Track the forms
  separately and defer compatibility translations until the native set is ready.
- **Compact control flow.** Expand each operand constraint in Table A.29.
  Check overlapping encodings using raw words, then audit link values, taken
  and untaken paths, and forbidden-slot behavior. `beqc` and `bovc` use
  the old `addi` primary opcode. The presence of that legacy instruction
  in MARS64 makes decoding the same words a migration risk.
- **PC-relative and memory operations.** Audit the entire family in
  Tables A.27 and A.28, plus word `ll`/`sc` and the Release 6 encodings
  for doubleword reservations. Keep immediate scaling and alignment
  constraints in the inventory alongside instruction words.
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
