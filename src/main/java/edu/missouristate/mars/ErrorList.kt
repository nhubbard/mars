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

@file:Suppress("MemberVisibilityCanBePrivate")

package edu.missouristate.mars

import java.io.File

/**
 * Maintains a list of generated error messages, regardless of source (tokenizing, parsing, assembly, or execution).
 *
 * @author Pete Sanderson
 * @version August 2003
 */
class ErrorList {
    companion object {
        const val ERROR_MESSAGE_PREFIX = "Error"
        const val WARNING_MESSAGE_PREFIX = "Warning"
        const val FILENAME_PREFIX = " in "
        const val LINE_PREFIX = " line "
        const val POSITION_PREFIX = " column "
        const val MESSAGE_SEPARATOR = ": "
    }

    val messages: ArrayList<ErrorMessage> = arrayListOf()
    var errorCount: Int = 0
        private set
    var warningCount: Int = 0
        private set

    /**
     * Determine whether error(s) have occurred or not.
     * @return `true` if error(s) have occurred. Does not include warnings.
     */
    val hasErrors: Boolean
        @JvmName("hasErrors") get() = errorCount > 0

    @Deprecated(
        "Use hasErrors property instead.",
        ReplaceWith("hasErrors"),
        DeprecationLevel.ERROR
    )
    fun errorsOccurred(): Boolean = hasErrors

    /**
     * Determine whether warning(s) have occurred or not.
     * @return `true` if warnings(s) have occurred. Does not include errors.
     */
    val hasWarnings: Boolean
        @JvmName("hasWarnings") get() = warningCount > 0

    @Deprecated(
        "Use hasWarnings property instead.",
        ReplaceWith("hasWarnings"),
        DeprecationLevel.ERROR
    )
    fun warningsOccurred(): Boolean = hasWarnings

    /**
     * Check if the error limit has been exceeded.
     * @return `true` if the error limit has been exceeded.
     */
    val isErrorLimitExceeded: Boolean get() = errorCount > Globals.maximumErrorMessages

    /**
     * Add a new error message to the end of the list.
     * @param message The ErrorMessage to add to the end of the list.
     * @param index Position in the error list.
     */
    @JvmOverloads
    fun add(message: ErrorMessage, index: Int = messages.size) {
        if (errorCount > Globals.maximumErrorMessages) return
        if (errorCount == Globals.maximumErrorMessages) {
            messages.add(
                ErrorMessage(
                    null,
                    message.line,
                    message.position,
                    message = "Error limit of ${Globals.maximumErrorMessages} exceeded."
                )
            )
            return
        }
        messages.add(index, message)
        if (message.isWarning) warningCount++ else errorCount++
    }

    @Deprecated(
        "Use Globals accessor instead.",
        ReplaceWith("Globals.maximumErrorMessages"),
        DeprecationLevel.ERROR
    )
    fun getErrorLimit(): Int = Globals.maximumErrorMessages

    /**
     * Produce a report for either warnings or errors.
     * @param isWarning `true` if you want a warnings report; `false` otherwise.
     */
    @JvmOverloads
    fun generateReport(isWarning: Boolean = false) = buildString {
        var reportLine: String
        for (i in messages.indices) {
            val m = messages[i]
            if ((isWarning && m.isWarning) || (!isWarning && !m.isWarning)) {
                reportLine = "${if (isWarning) WARNING_MESSAGE_PREFIX else ERROR_MESSAGE_PREFIX}$FILENAME_PREFIX"
                if (m.filename.isNotEmpty()) reportLine += File(m.filename).path
                if (m.line > 0) reportLine += "$LINE_PREFIX${m.getMacroExpansionHistory()}${m.line}"
                if (m.position > 0) reportLine += "$POSITION_PREFIX${m.position}"
                reportLine += "$MESSAGE_SEPARATOR${m.message}"
                appendLine(reportLine)
            }
        }
    }

    @Deprecated(
        "Use generateReport directly.",
        ReplaceWith("generateReport()"),
        DeprecationLevel.ERROR
    )
    fun generateErrorReport(): String = generateReport()

    @Deprecated(
        "Use generateReport directly.",
        ReplaceWith("generateReport(true)"),
        DeprecationLevel.ERROR
    )
    fun generateWarningReport(): String = generateReport(true)

    fun generateErrorAndWarningReport(): String = generateReport() + generateReport(false)
}