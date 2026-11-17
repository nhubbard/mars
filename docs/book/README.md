# MARS book instructional drafts

These drafts develop the [chapter ideas](../textbook-chapter-ideas.md) into
lessons for introductory assembly language and computer architecture students
using the IntelliJ MARS plugin. The opening sequence assumes ordinary integer
arithmetic and introduces assembly syntax as it is needed.

## First instructional sequence

[Observing a MARS program](01-observing-a-program.md) has five incremental steps:

1. Predict and run a console calculation.
2. Stop at a breakpoint and trace two arithmetic instructions.
3. Follow arguments and results across a console syscall.
4. Load, calculate, store, and reload a word in memory.
5. Compare word and doubleword arithmetic at a signed boundary.

The [companion examples](../../examples/book) include checks for the original
programs. Each lesson moves from prediction to observation, explanation, and a
small modification. Readers should keep a short record of expected and observed
states; a correct printed answer alone does not explain the calculation.

## Proposed next increments

| Increment | New idea | Concrete result |
| --- | --- | --- |
| One pixel | An address can identify a display cell | Store an RGB word and predict a color change |
| Eight sprite rows | Bytes, bits, masks, and shifts | Decode one row of an 8 by 8 sprite by hand |
| A counted loop | Control flow and a loop invariant | Draw every row and verify the exact number of stores |
| A drawing procedure | Arguments, return values, and register preservation | Draw the same sprite at two positions |
| Measuring a correct program | Workload contracts and completed instruction counts | Compare two sprite implementations with equal output |

The control-flow lesson should teach the current Release 6 behavior explicitly:
retained delayed transfers execute a delay-slot instruction; compact transfers
have different rules. Its runnable examples should establish those rules before
asking students to rearrange instructions.

## Author verification

The companion grading scripts check intermediate registers, memory changes,
console output, and the full 64-bit results in the width example. Run them with
JDK 25 using the commands in the [example instructions](../../examples/book/README.md).
They check the supplied originals; the practice modifications need their own
expected results.

Before classroom publication, walk through the breakpoint and memory-view
instructions in the packaged IntelliJ plugin and collect feedback from a reader
new to assembly. This draft's program states have executable checks; its teaching
sequence still needs that reader trial.
