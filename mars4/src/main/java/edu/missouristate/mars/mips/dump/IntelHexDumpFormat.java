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

package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Memory;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Intel's Hex memory initialization format
 *
 * @author Leo Alterman
 * @version July 2011
 */

public class IntelHexDumpFormat extends AbstractDumpFormat {

    /**
     * Constructor.  File extention is "hex".
     */
    public IntelHexDumpFormat() {
        super("Intel hex format", "HEX", "Written as Intel Hex Memory File", "hex");
    }

    /**
     * Write MIPS memory contents according to the Memory Initialization File
     * (MIF) specification.
     *
     * @param file         File in which to store MIPS memory contents.
     * @param firstAddress first (lowest) memory address to dump.  In bytes but
     *                     must be on word boundary.
     * @param lastAddress  last (highest) memory address to dump.  In bytes but
     *                     must be on word boundary.  Will dump the word that starts at this address.
     * @throws AddressErrorException if firstAddress is invalid or not on a word boundary.
     * @throws IOException           if error occurs during file output.
     */
    public void dumpMemoryRange(@NotNull File file, int firstAddress, int lastAddress)
            throws AddressErrorException, IOException {
        try (PrintStream out = new PrintStream(new FileOutputStream(file))) {
            StringBuilder string;
            for (int address = firstAddress; address <= lastAddress; address += Memory.WORD_LENGTH_BYTES) {
                Integer temp = Globals.memory.getRawWordOrNull(address);
                if (temp == null)
                    break;
                string = new StringBuilder(Integer.toHexString(temp));
                while (string.length() < 8) {
                    string.insert(0, '0');
                }
                StringBuilder addr = new StringBuilder(Integer.toHexString(address - firstAddress));
                while (addr.length() < 4) {
                    addr.insert(0, '0');
                }
                String chksum;
                int tmp_chksum = 0;
                tmp_chksum += 4;
                tmp_chksum += 0xFF & (address - firstAddress);
                tmp_chksum += 0xFF & ((address - firstAddress) >> 8);
                tmp_chksum += 0xFF & temp;
                tmp_chksum += 0xFF & (temp >> 8);
                tmp_chksum += 0xFF & (temp >> 16);
                tmp_chksum += 0xFF & (temp >> 24);
                tmp_chksum = tmp_chksum % 256;
                tmp_chksum = ~tmp_chksum + 1;
                chksum = Integer.toHexString(0xFF & tmp_chksum);
                if (chksum.length() == 1) chksum = '0' + chksum;
                String finalstr = ":04" + addr + "00" + string + chksum;
                out.println(finalstr.toUpperCase());
            }
            out.println(":00000001FF");
        }

    }
}
