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

package edu.missouristate.mars.util;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Memory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;

public class MemoryDump {

    /**
     * A list of segmentname/dumpformat/filename triples which should be dumped
     */
    public static @Nullable ArrayList<String[]> dumpTriples = null;

    private static final String[] segmentNames = {".text", ".data"};
    private static final int[] baseAddresses = new int[2];
    private static final int[] limitAddresses = new int[2];


    /**
     * Return array with segment address bounds for specified segment.
     *
     * @param segment String with segment name (initially ".text" and ".data")
     * @return array of two Integer, the base and limit address for that segment.  Null if parameter
     * name does not match a known segment name.
     */

    public static Integer @Nullable [] getSegmentBounds(String segment) {
        for (int i = 0; i < segmentNames.length; i++) {
            if (segmentNames[i].equals(segment)) {
                Integer[] bounds = new Integer[2];
                bounds[0] = getBaseAddresses(segmentNames)[i];
                bounds[1] = getLimitAddresses(segmentNames)[i];
                return bounds;
            }
        }
        return null;
    }


    /**
     * Get the names of segments available for memory dump.
     *
     * @return array of Strings, each string is segment name (e.g. ".text", ".data")
     */

    public static String[] getSegmentNames() {
        return segmentNames;
    }


    /**
     * Get the MIPS memory base address(es) of the specified segment name(s).
     * If invalid segment name is provided, will throw NullPointerException, so
     * I recommend getting segment names from getSegmentNames().
     *
     * @param segments Array of Strings containing segment names (".text", ".data")
     * @return Array of int containing corresponding base addresses.
     */
    public static int[] getBaseAddresses(String[] segments) {
        baseAddresses[0] = Memory.textBaseAddress;
        baseAddresses[1] = Memory.dataBaseAddress;
        return baseAddresses;
    }


    /**
     * Get the MIPS memory limit address(es) of the specified segment name(s).
     * If invalid segment name is provided, will throw NullPointerException, so
     * I recommend getting segment names from getSegmentNames().
     *
     * @param segments Array of Strings containing segment names (".text", ".data")
     * @return Array of int containing corresponding limit addresses.
     */
    public static int[] getLimitAddresses(String[] segments) {
        limitAddresses[0] = Memory.textLimitAddress;
        limitAddresses[1] = Memory.dataSegmentLimitAddress;
        return limitAddresses;
    }


    /**
     * Look for first "null" memory value in an address range.  For text segment (binary code), this
     * represents a word that does not contain an instruction.  Normally use this to find the end of
     * the program.  For data segment, this represents the first block of simulated memory (block length
     * currently 4K words) that has not been referenced by an assembled/executing program.
     *
     * @param baseAddress  lowest MIPS address to be searched; the starting point
     * @param limitAddress highest MIPS address to be searched
     * @return lowest address within specified range that contains "null" value as described above.
     * @throws AddressErrorException if the base address is not on a word boundary
     */
    public static int getAddressOfFirstNull(int baseAddress, int limitAddress) throws AddressErrorException {
        int address = baseAddress;
        for (; address < limitAddress; address += Memory.WORD_LENGTH_BYTES) {
            if (Globals.memory.getRawWordOrNull(address) == null) {
                break;
            }
        }
        return address;
    }

}