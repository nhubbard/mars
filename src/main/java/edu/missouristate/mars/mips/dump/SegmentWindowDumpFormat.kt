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

package edu.missouristate.mars.mips.dump

import edu.missouristate.mars.Globals
import edu.missouristate.mars.Settings
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.util.Binary
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream

class SegmentWindowDumpFormat : AbstractDumpFormat(
    "Text/Data Segment Window",
    "SegmentWindow",
    "Text Segment or Data Segment Window format as text file",
    "txt"
) {
    override fun dumpMemoryRange(file: File?, firstAddress: Int, lastAddress: Int) {
        val hexAddresses = Globals.settings.getBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX)
        val hexValues = Globals.settings.getBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX)
        file?.let { f ->
            FileOutputStream(f).use { out ->
                PrintStream(out).use {
                    if (Memory.inDataSegment(firstAddress)) {
                        var offset = 0
                        var string = StringBuilder()
                        for (address in firstAddress..lastAddress step Memory.WORD_LENGTH_BYTES) {
                            if (offset % 8 == 0)
                                string = StringBuilder("${if (hexAddresses) Binary.intToHexString(address) else Binary.unsignedIntToIntString(address)}    ")
                            offset++
                            val temp = Globals.memory.getRawWordOrNull(address) ?: break
                            string.append(if (hexValues) Binary.intToHexString(temp) else "           $temp".substring(temp.toString().length)).append(" ")
                            if (offset % 8 == 0) {
                                it.println(string)
                                string = StringBuilder()
                            }
                        }
                        return
                    }
                    if (!Memory.inTextSegment(firstAddress)) return
                    it.println(" Address    Code        Basic                     Source")
                    it.println()
                    var string: String
                    for (address in firstAddress..lastAddress step Memory.WORD_LENGTH_BYTES) {
                        string = "${if (hexAddresses) Binary.intToHexString(address) else Binary.unsignedIntToIntString(address)}  "
                        val temp = Globals.memory.getRawWordOrNull(address) ?: break
                        string += "${Binary.intToHexString(temp)}  "
                        try {
                            val ps = Globals.memory.getStatement(address)
                            string += "${ps?.getPrintableBasicAssemblyStatement()}                      ".substring(0, 22)
                            string += ((if (ps?.getSource() == "") "" else Integer.valueOf(ps?.getSourceLine() ?: 0).toString()) + "     ").substring(0, 5)
                            string += ps?.getSource()
                        } catch (ignored: AddressErrorException) {}
                        it.println(string)
                    }
                }
            }
        }
    }
}