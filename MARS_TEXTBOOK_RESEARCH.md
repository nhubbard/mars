# Initial research for a MARS 5 assembly textbook

## Source and adoption

Charles W. Kann’s 2015 *Introduction To MIPS Assembly Language Programming* is the closest
starting point. It introduces programming through MARS and covers representation, arithmetic,
instruction encoding, procedures, memory, control flow, stack frames, and arrays. Its first
programming chapter walks through launching the desktop JAR and using the original editor and
simulator. The Gettysburg College repository lists the book under CC BY 4.0, and the LibreTexts
edition provides chapter-level navigation and identifies its cover thumbnail under a separate
license. [Gettysburg College edition](https://cupola.gettysburg.edu/oer/2/),
[original programming chapter](https://faculty.winthrop.edu/thackerw/CSCI211/Introduction%20To%20MIPS%20Assembly%20Language%20Programming.pdf#page=37),
[LibreTexts edition](https://eng.libretexts.org/Bookshelves/Computer_Science/Programming_Languages/Introduction_To_MIPS_Assembly_Language_Programming_%28Kann%29)

Johns Hopkins lists Kann in its Foundations of Computer Architecture course, and a UC Berkeley
CS61C lab uses MARS for stepping and function exercises. UT Dallas pairs MARS with the MIPS
edition of Patterson and Hennessy for a broader computer-organization course. These are two
adoption paths for a MARS 5 book. It could replace the introductory assembly reading and labs,
and serve as a lab companion where the assigned textbook also teaches pipelines, caches, and
hardware design. Ed Jorgensen’s shorter QtSpim book is a useful scope comparison and has a
different license, CC BY-NC-SA. [Johns Hopkins syllabus](https://apps.ep.jhu.edu/syllabus/fall-2024/605.611.8VL2),
[Berkeley MARS lab](https://www-inst.eecs.berkeley.edu/~cs61c/sp17/labs/3/),
[UT Dallas syllabus](https://dox.utdallas.edu/syl161567),
[Marshall University QtSpim listing](https://mds.marshall.edu/oa-textbooks/630/)

## Editorial approach

Use Kann’s topic sequence as the coverage baseline and write the IntelliJ labs and runnable
examples for MARS 5. Where the book adapts CC BY text or figures, credit Kann, link the source
and license, and describe the edits. Keep a record per reused asset with its author, edition,
source, license, and changes. The Gettysburg entry links a companion ZIP. Check the license of
each asset before reuse. Capture fresh IntelliJ screenshots, draw new diagrams where practical,
and write the examples used by the grader. [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/),
[Gettysburg companion resources](https://cupola.gettysburg.edu/oer/2/)

| Existing topic | Proposed MARS 5 lab |
| --- | --- |
| Number representation | Inspect one register in hexadecimal and decimal. Follow a signed overflow and use Backstep to inspect its inputs. |
| First programs and syscalls | Open a `.mars` file in IntelliJ, assemble and run a first program, add a breakpoint, read stdin, and reproduce the same output from the CLI. |
| Arithmetic and logic | Compare a basic instruction with a pseudo-instruction expansion. Inspect the assembled words and follow a bitfield operation. |
| Instruction encoding | Pack fields by hand, compare the result with an assembled word, and follow a branch displacement in disassembly. |
| Subprograms and memory | Trace arguments, returns, and register preservation. Use the memory view to distinguish bytes, words, and addresses. |
| Control flow and stack frames | Step through a loop, a nested call, and a recursive call. Explain an ordinary branch separately from a compact branch. |
| Arrays | Implement a search subroutine and grade its return value, array accesses, and intermediate state with a Kotlin assignment. |
| New material | Add a UTF-8 buffer exercise and an optional MARS64 chapter with doubleword data, register width, sign extension, and the existing MARS memory map. |

Teach MARS32 first to keep the main sequence usable in an existing introductory course. Mark
any opt-in feature and list the exact settings or CLI flags in the lab. Show the same code in
IntelliJ and the CLI so instructors can grade on a server using the sample assignment project.
Keep architecture diagrams adjacent to the related debugger task, with textual descriptions
that explain state changes independently of the screenshot. [Repository examples](examples),
[Assignment authoring guide](examples/assignment/README.md), [MARS dialect and target settings](README.md#architecture-target)

## LuaLaTeX feasibility

Use a pinned TeX Live release with LuaLaTeX and `latexmk` for repeatable PDF builds. Use
`fontspec` for OpenType font selection and test glyph coverage with the same UTF-8 strings
used in assembly. Keep the assembly and Kotlin grading files outside the TeX source and import
them as listings. This lets CI execute the exact files printed in the book. The first layout
test should compare Unicode verbatim output with syntax highlighting, checking copy and paste,
line wraps, and whether the listing works in a tagged PDF. The `fvextra` package supports
wrapping but is listed as incompatible with tagged output. Keep downloadable source beside
each listing and check accessibility before choosing the final package. [CTAN fontspec](https://ctan.org/pkg/fontspec),
[CTAN latexmk](https://ctan.org/pkg/latexmk), [CTAN fvextra](https://ctan.org/pkg/fvextra),
[LaTeX Tagged PDF project](https://latex3.github.io/tagging-project/)

Start with one chapter on arrays and byte-sized data. It should contain a runnable program, an
IntelliJ debug walkthrough, a matching CLI command, and a grading script that inspects an
intermediate register and the final bytes. Check that the PDF builds from a clean checkout,
every listed file assembles and runs with a pinned MARS release, and PDF text extraction keeps
the register names and Unicode examples. This would test the publishing workflow before
committing to the complete book.
