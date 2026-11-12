package edu.missouristate.mars.mips.dump

import edu.missouristate.mars.mips.dump.MemoryDumpSnapshot.Segment
import edu.missouristate.mars.util.Binary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

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

/** A text listing of the assembled instructions or grouped data words. */
open class SegmentWindowDumpFormat : AbstractDumpFormat(
    "Text/Data Segment Window", "SegmentWindow", " Text Segment Window or Data Segment Window format to text file", null,
) {
    override fun includesSourceListing(): Boolean = true

    @Throws(IOException::class)
    override fun write(file: File, snapshot: MemoryDumpSnapshot) {
        PrintStream(FileOutputStream(file)).use { out ->
            when (snapshot.segment) {
                Segment.DATA -> dumpData(snapshot, out)
                Segment.TEXT -> dumpText(snapshot, out)
                Segment.OTHER -> Unit
            }
            checkOutput(out)
        }
    }

    private fun dumpData(snapshot: MemoryDumpSnapshot, out: PrintStream) {
        val row = StringBuilder()
        for (index in 0 until snapshot.size()) {
            if (index % 8 == 0) row.append(formatAddress(snapshot, index)).append("    ")
            val word = snapshot.word(index)
            row.append(if (snapshot.hexValues) Binary.intToHexString(word) else word.toString().padStart(11)).append(' ')
            if (index % 8 == 7 || index == snapshot.size() - 1) {
                out.println(row)
                row.setLength(0)
            }
        }
    }

    private fun dumpText(snapshot: MemoryDumpSnapshot, out: PrintStream) {
        out.println(" Address    Code        Basic                     Source")
        out.println()
        for (index in 0 until snapshot.size()) {
            val line = snapshot.sourceLine(index)
            val sourceNumber = if (line.source.isEmpty()) "" else line.line.toString()
            out.println(
                formatAddress(snapshot, index) + "  " + Binary.intToHexString(snapshot.word(index)) + "  " +
                    line.instruction.padEnd(22).take(22) + sourceNumber.padEnd(5).take(5) + line.source,
            )
        }
    }

    private fun formatAddress(snapshot: MemoryDumpSnapshot, index: Int): String {
        val address = snapshot.address(index)
        return if (snapshot.hexAddresses) Binary.intToHexString(address) else Binary.unsignedIntToIntString(address)
    }
}
