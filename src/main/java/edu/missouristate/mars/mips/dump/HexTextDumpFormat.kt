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

class HexTextDumpFormat: AbstractDumpFormat(
    "Hexadecimal Text",
    "HexText",
    "Written as hex characters to text file",
    "txt"
) {
    /**
     * Write MIPS memory contents in hexadecimal text format.
     * Each line of text contains one memory word written in hexadecimal characters.
     */
    override fun dumpMemoryRange(file: File?, firstAddress: Int, lastAddress: Int) {
        /*
        Implementation without helper:
        file?.let { f ->
            FileOutputStream(f).use { out ->
                PrintStream(out).use {
                    var string: StringBuilder
                    var address = firstAddress
                    while (address <= lastAddress) {
                        val temp = Globals.memory.getRawWordOrNull(address) ?: break
                        string = StringBuilder(Integer.toHexString(temp))
                        while (string.length < 8) string.insert(0, '0')
                        it.println(string)
                        address += Memory.WORD_LENGTH_BYTES
                    }
                }
            }
        }
        */
        file.dumpMemoryAs(firstAddress, lastAddress) { it, _ ->
            println(buildString {
                append(Integer.toHexString(it))
                while (length < 8) insert(0, '0')
            })
        }
    }
}