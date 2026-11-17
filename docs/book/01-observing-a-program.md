# Observing a MARS program Draft

A program changes the state of a machine one instruction at a time. In this
lesson, you will predict those changes and observe them in MARS. You will read
a number, calculate a result, follow a value into memory, and explain why the
width of an arithmetic instruction matters.

You need IntelliJ IDEA with the MARS plugin installed. Open the
[`examples/book`](../../examples/book) directory as a project. The examples use
the current MIPS64 Release 6 environment and the educational MARS syscall
interface. You can begin with ordinary integer arithmetic; no previous assembly
language experience is assumed.

## Step 1 Run a calculation

Open [`01-console.asm`](../../examples/book/01-console.asm). Before running it,
calculate `2 × 20 + 1` on paper. Right-click the source file and choose its MARS
Run action. Enter `20` when the console asks for a small integer, then press
Enter. The program should report:

```text
Twice your number plus one is: 41
```

Repeat with `0` and `-3`. Predict both answers before running the program. Keep
the inputs small for now; Step 5 will explore the boundary of a word result.

Read the source from `main:` downward. A name followed by a colon is a
**label**: it gives a name to a location in the program. An instruction names an
operation and its operands. A line beginning with `#` is a comment for readers.
The `.data` section describes stored data, including the messages; `.text`
contains the instructions.

The calculation is concentrated in two instructions:

```asm
addu $t0, $v0, $v0
addiu $t0, $t0, 1
```

The names beginning with `$` identify **registers**, small storage locations in
the simulated processor. For these arithmetic instructions, the destination is
written first. The first instruction adds the value in `$v0` to itself and
writes the result into `$t0`. The second adds the immediate value `1`, a number
written directly in the instruction, to `$t0`.

**Check your understanding:** After the first instruction, what should `$t0`
contain for an input of `-3`? Which instruction changes that value to `-5`?

## Step 2 Stop before the calculation

Set a breakpoint on the `addu` instruction below `calculate:` by clicking in the
editor gutter. Start the file with its MARS Debug action and enter `20`. When
execution pauses, inspect the integer registers in the Debug tool window.

A breakpoint pauses execution before the instruction at that location runs.
At this stop, `$v0` should contain `20`; the addition below `calculate:` has
yet to execute. If the display uses hexadecimal, `20` appears as `0x14`,
possibly with leading zeroes.

Use **Step Over** once, then inspect `$t0`. Step again and inspect it a second
time. Record what you observe:

| Stop | Expected value |
| --- | --- |
| Before `addu` | `$v0 = 20` |
| After `addu` | `$t0 = 40` |
| After `addiu` | `$t0 = 41` |

The highlighted source location shows where execution will continue. A source
line can expand into several machine instructions. MARS accepts convenient
**pseudo-instructions**, including `li`, `la`, and `move`; their expansion
depends on the operation and operands. The two arithmetic instructions above
are basic instructions, so each provides a clear observation point for this
exercise.

Resume execution and compare the printed answer with the register value you
observed. Then change the immediate `1` to `3`. Predict the new result for `20`,
run the program, and explain which intermediate value stayed the same. Restore
the original instruction before continuing.

## Step 3 Follow the console interface

MARS programs request console services with `syscall`. The service number goes
in `$v0`. Other registers carry arguments or results according to that service's
contract.

| Service number in `$v0` | Request | Register to inspect |
| --- | --- | --- |
| `4` | Print a zero-terminated string | `$a0` holds the string address |
| `5` | Read a signed integer | `$v0` receives the integer |
| `1` | Print a signed integer | `$a0` holds the integer |
| `10` | Exit | No argument is needed |

Find the `li $v0, 5` and the `syscall` immediately below it. Set a breakpoint on
that `syscall`, start a fresh Debug launch, and inspect `$v0` before executing
it. It contains `5`, the service number. Step over the syscall and provide
`20` when requested. At the next stop, `$v0` contains `20`, the returned value.

Now find `report:`. The program saves its arithmetic result in `$t0` before
using `$v0` for more service numbers. Later, `move $a0, $t0` supplies that result
to the print-integer service. A register's meaning comes from the program and
the interface being used; `$v0` can hold a service number at one moment and an
input value at another.

**Explain:** Why would moving the result into `$v0` just before `li $v0, 1`
lose the result? Trace the two writes rather than guessing from the register's
name.

## Step 4 Observe a value in memory

Open [`02-memory.asm`](../../examples/book/02-memory.asm). This program has no
console output. Its result is a change in memory, which you will inspect while
debugging.

The declarations reserve two adjacent words:

```asm
.align 2
input:  .word 21
result: .word 0
```

A **word** occupies four bytes. Memory addresses identify individual bytes, so
the address of `result` is four greater than the address of `input`. Here,
`.align 2` places the data at an address divisible by four. The `la` instruction
puts the address of `input` into `$t0`.

Set a breakpoint on the `lw` below `load_value:` and start Debug. Open the memory view in the
Debug tool window. With the default memory layout, **Go to Region → Data**
takes you to the region containing these two words. Inspect the words as
integers; a raw byte display also depends on the selected byte order.

Predict each change before stepping:

1. `lw $t1, 0($t0)` loads the word at the address in `$t0` into `$t1`.
   The expected value is `21`.
2. `addu $t2, $t1, $t1` produces `42` in `$t2`. The word at `result`
   still contains `0`.
3. `sw $t2, 4($t0)` stores the low 32 bits of `$t2` at the address four
   bytes beyond `$t0`. The word at `result` becomes `42`.
4. `lw $t3, 4($t0)` loads that word back into `$t3`.

Register arithmetic and memory stores are separate operations. Computing `42`
does not by itself update the word at `result`.

Change the initial input word to `-3` and repeat. Expect `$t1 = -3`,
`$t2 = -6`, the stored word `-6`, and `$t3 = -6`. Restore `21` afterward.

**Explain:** What would change if the store used `0($t0)`? Predict both words
and the value loaded into `$t3`, then try it. Restore `4($t0)` afterward.

## Step 5 Compare word and doubleword arithmetic

Open [`03-width.asm`](../../examples/book/03-width.asm). MARS has 64-bit integer
registers, but the instruction determines the width of an arithmetic operation.
The program starts with `$t0 = 0x000000007fffffff`, or `2147483647` in decimal,
and adds one in two ways:

```asm
addiu  $t1, $t0, 1
daddiu $t2, $t0, 1
```

Set a breakpoint on the `addiu` below `word_step:`. Debug the program and inspect the full
64-bit register values after each addition.

| Destination | Hexadecimal result | Signed decimal interpretation |
| --- | --- | --- |
| `$t1` | `0xffffffff80000000` | `-2147483648` |
| `$t2` | `0x0000000080000000` | `2147483648` |

`addiu` produces a 32-bit word result and **sign-extends** it into the 64-bit
destination: it copies bit 31 into the upper 32 bits. The word result has bit
31 set, so its signed interpretation is negative. `daddiu` operates on a
64-bit doubleword; the positive result fits in that width.

The `u` in these instruction names means that these additions do not trap on
signed overflow. It does not make a word result zero-extend into its
destination register. Track the operation's width and the resulting bits.

**Try another boundary:** Change the starting value to `0x7ffffffe`. Predict
both results. Then try `-1`. In each case, write the hexadecimal result and its
signed decimal interpretation before running. Restore the original value when
finished.

## Practice Explain a program state

Return to the original examples and answer these questions with a register or
memory observation supporting each answer.

1. In the console example, where is the input immediately after the read
   syscall? Where is the arithmetic result while the answer message prints?
2. In the memory example, when does `result` first become `42`? Why does its
   address differ from `input` by four?
3. In the width example, why can adding one to the same starting value produce
   two different signed results?
4. Change the console calculation to `3 × x + 1` using additions. Verify inputs
   `20`, `0`, and `-3`. State your expected intermediate values before stepping.

For the fourth exercise, use small inputs whose intermediate values fit in a
signed 32-bit word. Later lessons will develop explicit contracts for arithmetic
boundaries and teach branches, loops, and procedure calls.
