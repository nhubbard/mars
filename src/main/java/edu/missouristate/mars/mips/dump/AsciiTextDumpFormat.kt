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

import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.util.Binary
import java.io.File
import java.io.IOException

// Slight change in functionality: added "txt" extension.
class AsciiTextDumpFormat : AbstractDumpFormat(
    "ASCII Text",
    "AsciiText",
    "Memory contents interpreted as ASCII characters",
    "txt"
) {
    /**
     * Interpret MIPS memory contents as ASCII characters.
     * Each line of text contains one memory word written in ASCII characters.
     * Those corresponding to tab, newline, null,
     * etc. are rendered as a backslash followed by a single-character code, such as \t for tab, or \0 for null.
     * Non-printable characters (control codes, or values beyond 127) are rendered as a period.
     *
     * @param file The file in which to store MIPS memory contents.
     * @param firstAddress The lowest memory address to dump. In bytes, but must be on a word boundary.
     * @param lastAddress The highest memory address to dump. In byte, but must be on a word boundary. Will dump the
     * word that starts at this address.
     *
     * @throws AddressErrorException if [firstAddress] is invalid or not on a word boundary.
     * @throws IOException if an error occurs during file output.
     */
    @Throws(AddressErrorException::class, IOException::class)
    override fun dumpMemoryRange(file: File?, firstAddress: Int, lastAddress: Int) {
        /*
        Implementation without helper function:
        file?.let {
            FileOutputStream(it).use {
                PrintStream(it).use {
                    var address = firstAddress
                    while (address <= lastAddress) {
                        val temp = Globals.memory.getRawWordOrNull(address) ?: break
                        it.println(Binary.intToAscii(temp))
                        address += Memory.WORD_LENGTH_BYTES
                    }
                }
            }
        }
        */
        file.dumpMemoryAs(firstAddress, lastAddress) { it, _ ->
            println(Binary.intToAscii(it))
        }
    }
}