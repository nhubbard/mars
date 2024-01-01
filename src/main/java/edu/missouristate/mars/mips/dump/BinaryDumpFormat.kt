/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
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

package edu.missouristate.mars.mips.dump

import java.io.File

// Slight change: the file extension has been set to "bin".
class BinaryDumpFormat: AbstractDumpFormat(
    "Binary",
    "Binary",
    "Written as byte stream to binary file",
    "bin"
) {
    /**
     * Write MIPS memory contents in pure binary format.
     */
    override fun dumpMemoryRange(file: File?, firstAddress: Int, lastAddress: Int) {
        /*
        Implementation without helper:

        file?.let {
            FileOutputStream(it).use {
                PrintStream(it).use {
                    var address = firstAddress
                    while (address <= lastAddress) {
                        val word = Globals.memory.getRawWordOrNull(address) ?: break
                        for (i in 0..<4) it.write(word ushr (i shl 3) and 0xFF)
                        address += Memory.WORD_LENGTH_BYTES
                    }
                }
            }
        }
        */
        file.dumpMemoryAs(firstAddress, lastAddress) { it, _ ->
            for (i in 0..<4) write(it ushr (i shl 3) and 0xFF)
        }
    }
}