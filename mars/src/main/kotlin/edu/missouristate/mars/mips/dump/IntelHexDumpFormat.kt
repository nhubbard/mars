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

package edu.missouristate.mars.mips.dump

import java.io.File

class IntelHexDumpFormat: AbstractDumpFormat(
    "Intel Memory Initialization Format",
    "IntelHexDump",
    "Written as Intel Memory Initialization Format (MIF) file",
    "mif"
) {
    override fun dumpMemoryRange(file: File?, firstAddress: Int, lastAddress: Int) {
        file.dumpMemoryAs(firstAddress, lastAddress, lastLine = ":00000001FF") { it, address ->
            val string = StringBuilder(Integer.toHexString(it))
            while (string.length < 8) string.insert(0, '0')
            val addr = StringBuilder(Integer.toHexString(address - firstAddress))
            while (addr.length < 4) addr.insert(0, '0')
            val checksum = getChecksum(firstAddress, address, it)
            val finalString = ":04${addr}00$string$checksum"
            println(finalString.uppercase())
        }
    }

    private fun getChecksum(firstAddress: Int, address: Int, temp: Int): String {
        var checksum: String
        var tempChecksum = 0
        tempChecksum += 4
        tempChecksum += 0xFF and (address - firstAddress)
        tempChecksum += 0xFF and ((address - firstAddress) shr 8)
        tempChecksum += 0xFF and temp
        tempChecksum += 0xFF and (temp shr 8)
        tempChecksum += 0xFF and (temp shr 16)
        tempChecksum += 0xFF and (temp shr 24)
        tempChecksum %= 256
        tempChecksum = tempChecksum.inv() + 1
        checksum = Integer.toHexString(0xFF and tempChecksum)
        if (checksum.length == 1) checksum = "0$checksum"
        return checksum
    }
}