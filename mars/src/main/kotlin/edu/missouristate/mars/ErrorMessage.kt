/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars

import java.util.regex.Pattern

/**
 * Represents the occurrence of an error detected during tokenization, assembly, or simulation.
 *
 * @author Pete Sanderson
 * @version August 2003
 */
class ErrorMessage {
    companion object {
        @JvmStatic
        private fun getExpansionHistory(sourceProgram: MIPSProgram?): String =
            sourceProgram?.getLocalMacroPool()?.getExpansionHistory() ?: ""
    }

    var isWarning: Boolean = false
        private set
    var filename: String = ""
        private set
    var line: Int = 0
        private set
    var position: Int = 0
        private set
    var message: String = ""
        private set
    private var macroExpansionHistory: String? = null

    /**
     * Standard constructor.
     *
     * @param filename The name of the source file in which the error occurred.
     * @param line The line number in the source program being processed when the error occurred.
     * @param position The position within the line being processed when the error occurred; typically the starting
     * position of the source token.
     * @param message The description of the error that occurred.
     * @param macroExpansionHistory Optional message to indicate that the error occurred during the expansion of a
     * macro.
     * @param isWarning Boolean variable to indicate if the message is actually a warning.
     */
    @JvmOverloads
    constructor(
        filename: String,
        line: Int,
        position: Int,
        message: String,
        macroExpansionHistory: String = "",
        isWarning: Boolean = false
    ) {
        this.filename = filename
        this.line = line
        this.position = position
        this.message = message
        this.macroExpansionHistory = macroExpansionHistory
        this.isWarning = isWarning
    }

    /**
     * Constructor that takes a MIPSProgram instance. Assumes that line number is calculated after any `.include` files
     * are expanded, and if there were, it will adjust the filename and line number so the message reflects the original
     * file and line number.
     *
     * @param sourceProgram The MIPSProgram instance in which this error occurs. Nullable.
     * @param line The line number in the source program being processed when the error occurred.
     * @param position The position within the line being processed when the error occurred; typically the starting
     * position of the source token.
     * @param message The description of the error that occurred.
     * @param isWarning Boolean variable to indicate if the message is actually a warning.
     */
    @JvmOverloads
    constructor(
        sourceProgram: MIPSProgram?,
        line: Int,
        position: Int,
        message: String = "",
        isWarning: Boolean = false
    ) {
        sourceProgram?.let { program ->
            program.getSourceLineList().let {
                val sourceLine = it[line - 1]
                filename = sourceLine.filename ?: ""
                this.line = sourceLine.lineNumber
            }
        } ?: run {
            filename = ""
            this.line = line
        }
        this.position = position
        this.message = message
        this.macroExpansionHistory = getExpansionHistory(sourceProgram)
        this.isWarning = isWarning
    }

    /**
     * Constructor used for runtime exceptions.
     *
     * @param statement The ProgramStatement object for the instruction causing the runtime error.
     * @param message The appropriate error message.
     */
    constructor(statement: ProgramStatement, message: String) {
        this.isWarning = true
        this.filename = statement.getSourceMipsProgram()?.getFilename() ?: ""
        this.position = 0
        this.message = message
        val defineLine = parseMacroHistory(statement.getSource())
        if (defineLine.isEmpty()) {
            this.line = statement.getSourceLine()
            this.macroExpansionHistory = ""
        } else {
            this.line = defineLine.first()
            this.macroExpansionHistory = statement.getSourceLine().toString()
        }
    }

    private fun parseMacroHistory(string: String): ArrayList<Int> {
        val pattern = Pattern.compile("<\\d+>")
        val matcher = pattern.matcher(string)
        var verify = string.trim()
        val macroHistory = arrayListOf<Int>()
        while (matcher.find()) {
            val match = matcher.group()
            if (verify.indexOf(match) == 0) {
                try {
                    val line = match.substring(1, match.length - 1).toInt()
                    macroHistory.add(line)
                } catch (e: NumberFormatException) {
                    break
                }
                verify = verify.substring(match.length).trim()
            } else break
        }
        return macroHistory
    }

    /**
     * Returns string describing macro expansion. Empty string if no macros were expanded.
     */
    fun getMacroExpansionHistory(): String {
        if (macroExpansionHistory.isNullOrEmpty()) return ""
        return "$macroExpansionHistory->"
    }
}
