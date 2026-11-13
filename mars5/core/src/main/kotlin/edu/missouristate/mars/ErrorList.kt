package edu.missouristate.mars

import edu.missouristate.mars.api.MarsSettings
import java.io.File

/*
Copyright (c) 2003-2012,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** Assembler and simulator diagnostics with independent error and warning counts. */
open class ErrorList @JvmOverloads constructor(private val sessionSettings: MarsSettings? = null) {
    private val messages = ArrayList<ErrorMessage>()
    private var errorCount = 0
    private var warningCount = 0

    open val errorMessages: ArrayList<ErrorMessage> get() = messages

    open fun errorsOccurred(): Boolean = errorCount != 0

    open fun warningsOccurred(): Boolean = warningCount != 0

    open fun add(message: ErrorMessage) {
        add(message, messages.size)
    }

    open fun add(message: ErrorMessage, index: Int) {
        if (errorCount > errorLimit) return
        if (errorCount == errorLimit) {
            messages.add(ErrorMessage(null, message.line, message.position, "Error Limit of $errorLimit exceeded."))
            errorCount++
            return
        }
        messages.add(index, message)
        if (message.isWarning) warningCount++ else errorCount++
    }

    open fun errorCount(): Int = errorCount

    open fun errorLimitExceeded(): Boolean = errorCount > errorLimit

    open val errorLimit: Int get() = sessionSettings?.assemblerErrorLimit ?: ApplicationInfo.maximumErrorMessages

    open fun generateErrorReport(): String = generateReport(isWarning = false)

    open fun generateWarningReport(): String = generateReport(isWarning = true)

    open fun generateErrorAndWarningReport(): String = generateWarningReport() + generateErrorReport()

    private fun generateReport(isWarning: Boolean): String = buildString {
        for (message in messages) {
            if (message.isWarning != isWarning) continue
            append(if (isWarning) WARNING_MESSAGE_PREFIX else ERROR_MESSAGE_PREFIX)
            append(FILENAME_PREFIX)
            val filename = message.filename!!
            if (filename.isNotEmpty()) append(File(filename).path)
            if (message.line > 0) append(LINE_PREFIX).append(message.macroExpansionHistory).append(message.line)
            if (message.position > 0) append(POSITION_PREFIX).append(message.position)
            append(MESSAGE_SEPARATOR).append(message.message).append('\n')
        }
    }

    companion object {
        const val ERROR_MESSAGE_PREFIX = "Error"
        const val WARNING_MESSAGE_PREFIX = "Warning"
        const val FILENAME_PREFIX = " in "
        const val LINE_PREFIX = " line "
        const val POSITION_PREFIX = " column "
        const val MESSAGE_SEPARATOR = ": "
    }
}
