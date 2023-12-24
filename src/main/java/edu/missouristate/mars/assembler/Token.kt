/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package edu.missouristate.mars.assembler

import edu.missouristate.mars.MIPSProgram

/**
 * Represents one token in the input MIPS program.
 * Each Token carries the position in which its source appears in the MIPS program alongside its type and value.
 *
 * @param type The [TokenTypes] entry for the token.
 * @param value The source code of this token.
 * @param sourceMipsProgram The program this token came from.
 * @param sourceLine The line number this token is generated from.
 * @param startPosition The character position within [sourceLine] this token came from.
 */
data class Token @JvmOverloads constructor(
    var type: TokenTypes,
    val value: String,
    val sourceMipsProgram: MIPSProgram,
    val sourceLine: Int,
    val startPosition: Int,
    private var originalProgramAndLine: Pair<MIPSProgram, Int> = sourceMipsProgram to sourceLine
) {
    /**
     * Set the original program and line number for this token.
     * The line number and/or program may change during pre-assembly as a result of using the `.include` directive,
     * and we need to keep the original for later reference (error message and text segment display).
     *
     * @param originalProgram The MIPS program containing this token.
     * @param originalSourceLine The line within that program of this token.
     */
    fun setOriginal(originalProgram: MIPSProgram, originalSourceLine: Int) {
        originalProgramAndLine = originalProgram to originalSourceLine
    }

    // Getters for the pair property that holds these two.
    // The setter sets both values at the same time.
    val originalProgram get() = originalProgramAndLine.first
    val originalSourceLine get() = originalProgramAndLine.second

    override fun toString(): String = value

    // Respelled items
    @Deprecated("Renamed to sourceMipsProgram to match convention.", ReplaceWith("sourceMipsProgram"))
    val sourceMIPSProgram get() = sourceMipsProgram

    @Deprecated("Renamed to startPosition to match convention.", ReplaceWith("startPosition"))
    val startPos get() = startPosition
}
