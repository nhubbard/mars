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

import edu.missouristate.mars.Globals
import edu.missouristate.mars.Settings
import edu.missouristate.mars.createProgram
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.withMutatedBoolean
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDumpFormats {
    private val lowAddress = Memory.textBaseAddress
    private val highAddress = Memory.textLimitAddress
    private val outFile = Paths.get("src/test/resources/test_dump").toFile()

    private fun testDumpOutput(dumper: DumpFormat, expected: IntArray, expectedReverse: IntArray = intArrayOf()) {
        // Create the output file if it doesn't exist, or clear its content out if it does exist
        if (!outFile.exists()) outFile.createNewFile()
        else outFile.writeBytes(byteArrayOf())
        createProgram("src/test/resources/tests/symboltable_two_labels.s")
        // Test with correct address range
        dumper.dumpMemoryRange(outFile, lowAddress, highAddress)
        val actual = outFile.readText().toCharArray().map { it.code }.toIntArray()
        assertArrayEquals(expected, actual)
        // Test with reversed address range
        dumper.dumpMemoryRange(outFile, highAddress, lowAddress)
        val empty = outFile.readText().toCharArray().map { it.code }.toIntArray()
        assertArrayEquals(expectedReverse, empty)
    }

    @Test
    fun testAsciiTextDumpFormat() {
        testDumpOutput(AsciiTextDumpFormat(), intArrayOf(
            32, 92, 98, 32, 32, 46, 32, 92, 48, 32, 32, 46, 10, 32, 32, 36, 32, 32, 46, 32, 92, 48, 32, 32, 46, 10, 32,
            32, 36, 32, 32, 46, 32, 92, 48, 32, 32, 46, 10, 32, 92, 48, 32, 92, 48, 32, 92, 48, 32, 92, 102, 10, 32, 92,
            98, 32, 32, 46, 32, 92, 48, 32, 32, 46, 10, 32, 32, 36, 32, 32, 46, 32, 92, 48, 32, 32, 46, 10, 32, 32, 36,
            32, 32, 46, 32, 92, 48, 32, 32, 46, 10, 32, 92, 48, 32, 92, 48, 32, 92, 48, 32, 92, 102, 10, 32, 32, 36, 32,
            32, 46, 32, 92, 48, 32, 92, 110, 10, 32, 92, 48, 32, 92, 48, 32, 92, 48, 32, 92, 102, 10
        ))
    }

    @Test
    fun testBinaryDumpFormat() {
        testDumpOutput(BinaryDumpFormat(), intArrayOf(
            1, 0, 16, 8, 1, 0, 2, 36, 1, 0, 4, 36, 12, 0, 0, 0, 5, 0, 16, 8,
            1, 0, 2, 36, 2, 0, 4, 36, 12, 0, 0, 0, 10, 0, 2, 36, 12, 0, 0, 0
        ))
    }

    @Test
    fun testBinaryTextDumpFormat() {
        testDumpOutput(BinaryTextDumpFormat(), intArrayOf(
            48, 48, 48, 48, 49, 48, 48, 48, 48, 48, 48, 49, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 48, 49, 10, 48, 48, 49, 48, 48, 49, 48, 48, 48, 48, 48, 48, 48, 48, 49, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 49, 10, 48, 48, 49, 48, 48, 49, 48, 48, 48, 48, 48, 48, 48, 49, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 49, 10, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 49, 49, 48, 48, 10, 48, 48, 48,
            48, 49, 48, 48, 48, 48, 48, 48, 49, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 49,
            48, 49, 10, 48, 48, 49, 48, 48, 49, 48, 48, 48, 48, 48, 48, 48, 48, 49, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 49, 10, 48, 48, 49, 48, 48, 49, 48, 48, 48, 48, 48, 48, 48, 49, 48, 48, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 49, 48, 10, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 49, 49, 48, 48, 10, 48, 48, 49, 48, 48, 49,
            48, 48, 48, 48, 48, 48, 48, 48, 49, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 49, 48, 49, 48, 10,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 49, 49, 48, 48, 10
        ))
    }

    @Test
    fun testHexTextDumpFormat() {
        testDumpOutput(HexTextDumpFormat(), intArrayOf(
            48, 56, 49, 48, 48, 48, 48, 49, 10, 50, 52, 48, 50, 48, 48, 48, 49, 10, 50, 52, 48, 52, 48, 48, 48, 49, 10,
            48, 48, 48, 48, 48, 48, 48, 99, 10, 48, 56, 49, 48, 48, 48, 48, 53, 10, 50, 52, 48, 50, 48, 48, 48, 49, 10,
            50, 52, 48, 52, 48, 48, 48, 50, 10, 48, 48, 48, 48, 48, 48, 48, 99, 10, 50, 52, 48, 50, 48, 48, 48, 97, 10,
            48, 48, 48, 48, 48, 48, 48, 99, 10
        ))
    }

    @Test
    fun testIntelHexDumpFormat() {
        testDumpOutput(IntelHexDumpFormat(), intArrayOf(
            58, 48, 52, 48, 48, 48, 48, 48, 48, 48, 56, 49, 48, 48, 48, 48, 49, 69, 51, 10, 58, 48, 52, 48, 48, 48, 52,
            48, 48, 50, 52, 48, 50, 48, 48, 48, 49, 68, 49, 10, 58, 48, 52, 48, 48, 48, 56, 48, 48, 50, 52, 48, 52, 48,
            48, 48, 49, 67, 66, 10, 58, 48, 52, 48, 48, 48, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 67, 69, 52, 10, 58,
            48, 52, 48, 48, 49, 48, 48, 48, 48, 56, 49, 48, 48, 48, 48, 53, 67, 70, 10, 58, 48, 52, 48, 48, 49, 52, 48,
            48, 50, 52, 48, 50, 48, 48, 48, 49, 67, 49, 10, 58, 48, 52, 48, 48, 49, 56, 48, 48, 50, 52, 48, 52, 48, 48,
            48, 50, 66, 65, 10, 58, 48, 52, 48, 48, 49, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 67, 68, 52, 10, 58, 48,
            52, 48, 48, 50, 48, 48, 48, 50, 52, 48, 50, 48, 48, 48, 65, 65, 67, 10, 58, 48, 52, 48, 48, 50, 52, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 67, 67, 67, 10, 58, 48, 48, 48, 48, 48, 48, 48, 49, 70, 70, 10
        ), intArrayOf(58, 48, 48, 48, 48, 48, 48, 48, 49, 70, 70, 10))
    }

    @Test
    fun testIntelHexDumpFormatWithSingleDigitChecksum() {
        // Create the output file if it doesn't exist, or clear its content out if it does exist
        if (!outFile.exists()) outFile.createNewFile()
        else outFile.writeBytes(byteArrayOf())
        Globals.initialize(false)
        for (i in Memory.dataBaseAddress..(Memory.dataBaseAddress + 256) step 4)
            Globals.memory.setRawWord(i, 0)
        IntelHexDumpFormat().dumpMemoryRange(outFile, Memory.dataBaseAddress, Memory.dataBaseAddress + 256)
        val actual = outFile.readText().toCharArray().map { it.code }.toIntArray()
        assertArrayEquals(intArrayOf(
            58, 48, 52, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 70, 67, 10, 58, 48, 52, 48, 48, 48, 52,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 70, 56, 10, 58, 48, 52, 48, 48, 48, 56, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 70, 52, 10, 58, 48, 52, 48, 48, 48, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 70, 48, 10, 58,
            48, 52, 48, 48, 49, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 69, 67, 10, 58, 48, 52, 48, 48, 49, 52, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 69, 56, 10, 58, 48, 52, 48, 48, 49, 56, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 69, 52, 10, 58, 48, 52, 48, 48, 49, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 69, 48, 10, 58, 48,
            52, 48, 48, 50, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 68, 67, 10, 58, 48, 52, 48, 48, 50, 52, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 68, 56, 10, 58, 48, 52, 48, 48, 50, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 68, 52, 10, 58, 48, 52, 48, 48, 50, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 68, 48, 10, 58, 48, 52,
            48, 48, 51, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 67, 67, 10, 58, 48, 52, 48, 48, 51, 52, 48, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 67, 56, 10, 58, 48, 52, 48, 48, 51, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            67, 52, 10, 58, 48, 52, 48, 48, 51, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 67, 48, 10, 58, 48, 52, 48,
            48, 52, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 66, 67, 10, 58, 48, 52, 48, 48, 52, 52, 48, 48, 48, 48,
            48, 48, 48, 48, 48, 48, 66, 56, 10, 58, 48, 52, 48, 48, 52, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 66,
            52, 10, 58, 48, 52, 48, 48, 52, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 66, 48, 10, 58, 48, 52, 48, 48,
            53, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 65, 67, 10, 58, 48, 52, 48, 48, 53, 52, 48, 48, 48, 48, 48,
            48, 48, 48, 48, 48, 65, 56, 10, 58, 48, 52, 48, 48, 53, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 65, 52,
            10, 58, 48, 52, 48, 48, 53, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 65, 48, 10, 58, 48, 52, 48, 48, 54,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 57, 67, 10, 58, 48, 52, 48, 48, 54, 52, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 48, 57, 56, 10, 58, 48, 52, 48, 48, 54, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 57, 52, 10,
            58, 48, 52, 48, 48, 54, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 57, 48, 10, 58, 48, 52, 48, 48, 55, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 56, 67, 10, 58, 48, 52, 48, 48, 55, 52, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 56, 56, 10, 58, 48, 52, 48, 48, 55, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 56, 52, 10, 58,
            48, 52, 48, 48, 55, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 56, 48, 10, 58, 48, 52, 48, 48, 56, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 55, 67, 10, 58, 48, 52, 48, 48, 56, 52, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 55, 56, 10, 58, 48, 52, 48, 48, 56, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 55, 52, 10, 58, 48,
            52, 48, 48, 56, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 55, 48, 10, 58, 48, 52, 48, 48, 57, 48, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 54, 67, 10, 58, 48, 52, 48, 48, 57, 52, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 54, 56, 10, 58, 48, 52, 48, 48, 57, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 54, 52, 10, 58, 48, 52,
            48, 48, 57, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 54, 48, 10, 58, 48, 52, 48, 48, 65, 48, 48, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 53, 67, 10, 58, 48, 52, 48, 48, 65, 52, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            53, 56, 10, 58, 48, 52, 48, 48, 65, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 53, 52, 10, 58, 48, 52, 48,
            48, 65, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 53, 48, 10, 58, 48, 52, 48, 48, 66, 48, 48, 48, 48, 48,
            48, 48, 48, 48, 48, 48, 52, 67, 10, 58, 48, 52, 48, 48, 66, 52, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 52,
            56, 10, 58, 48, 52, 48, 48, 66, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 52, 52, 10, 58, 48, 52, 48, 48,
            66, 67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 52, 48, 10, 58, 48, 52, 48, 48, 67, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 48, 48, 51, 67, 10, 58, 48, 52, 48, 48, 67, 52, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 51, 56,
            10, 58, 48, 52, 48, 48, 67, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 51, 52, 10, 58, 48, 52, 48, 48, 67,
            67, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 51, 48, 10, 58, 48, 52, 48, 48, 68, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 48, 50, 67, 10, 58, 48, 52, 48, 48, 68, 52, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 50, 56, 10,
            58, 48, 52, 48, 48, 68, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 50, 52, 10, 58, 48, 52, 48, 48, 68, 67,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 50, 48, 10, 58, 48, 52, 48, 48, 69, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 49, 67, 10, 58, 48, 52, 48, 48, 69, 52, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 49, 56, 10, 58,
            48, 52, 48, 48, 69, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 49, 52, 10, 58, 48, 52, 48, 48, 69, 67, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 49, 48, 10, 58, 48, 52, 48, 48, 70, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 48, 48, 67, 10, 58, 48, 52, 48, 48, 70, 52, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 56, 10, 58, 48,
            52, 48, 48, 70, 56, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 52, 10, 58, 48, 52, 48, 48, 70, 67, 48, 48,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 10, 58, 48, 52, 48, 49, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48,
            48, 70, 66, 10, 58, 48, 48, 48, 48, 48, 48, 48, 49, 70, 70, 10
        ), actual)
    }

    @Test
    fun testSegmentWindowDumpFormat() {
        testDumpOutput(SegmentWindowDumpFormat(), intArrayOf(
            32, 65, 100, 100, 114, 101, 115, 115, 32, 32, 32, 32, 67, 111, 100, 101, 32, 32, 32, 32, 32, 32, 32, 32, 66,
            97, 115, 105, 99, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 83,
            111, 117, 114, 99, 101, 10, 10, 48, 120, 48, 48, 52, 48, 48, 48, 48, 48, 32, 32, 48, 120, 48, 56, 49, 48,
            48, 48, 48, 49, 32, 32, 106, 32, 48, 120, 48, 48, 52, 48, 48, 48, 48, 52, 32, 32, 32, 32, 32, 32, 32, 32,
            32, 32, 50, 32, 32, 32, 32, 32, 32, 32, 32, 106, 32, 112, 114, 105, 110, 116, 95, 111, 110, 101, 10, 48,
            120, 48, 48, 52, 48, 48, 48, 48, 52, 32, 32, 48, 120, 50, 52, 48, 50, 48, 48, 48, 49, 32, 32, 97, 100, 100,
            105, 117, 32, 36, 50, 44, 36, 48, 44, 48, 120, 48, 48, 48, 48, 48, 48, 48, 49, 53, 32, 32, 32, 32, 32, 32,
            32, 32, 108, 105, 32, 36, 118, 48, 44, 32, 49, 10, 48, 120, 48, 48, 52, 48, 48, 48, 48, 56, 32, 32, 48, 120,
            50, 52, 48, 52, 48, 48, 48, 49, 32, 32, 97, 100, 100, 105, 117, 32, 36, 52, 44, 36, 48, 44, 48, 120, 48, 48,
            48, 48, 48, 48, 48, 49, 54, 32, 32, 32, 32, 32, 32, 32, 32, 108, 105, 32, 36, 97, 48, 44, 32, 49, 10, 48,
            120, 48, 48, 52, 48, 48, 48, 48, 99, 32, 32, 48, 120, 48, 48, 48, 48, 48, 48, 48, 99, 32, 32, 115, 121, 115,
            99, 97, 108, 108, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 55, 32, 32, 32, 32, 32, 32,
            32, 32, 115, 121, 115, 99, 97, 108, 108, 10, 48, 120, 48, 48, 52, 48, 48, 48, 49, 48, 32, 32, 48, 120, 48,
            56, 49, 48, 48, 48, 48, 53, 32, 32, 106, 32, 48, 120, 48, 48, 52, 48, 48, 48, 49, 52, 32, 32, 32, 32, 32,
            32, 32, 32, 32, 32, 56, 32, 32, 32, 32, 32, 32, 32, 32, 106, 32, 112, 114, 105, 110, 116, 95, 116, 119, 111,
            10, 48, 120, 48, 48, 52, 48, 48, 48, 49, 52, 32, 32, 48, 120, 50, 52, 48, 50, 48, 48, 48, 49, 32, 32, 97,
            100, 100, 105, 117, 32, 36, 50, 44, 36, 48, 44, 48, 120, 48, 48, 48, 48, 48, 48, 48, 49, 49, 49, 32, 32, 32,
            32, 32, 32, 32, 108, 105, 32, 36, 118, 48, 44, 32, 49, 10, 48, 120, 48, 48, 52, 48, 48, 48, 49, 56, 32, 32,
            48, 120, 50, 52, 48, 52, 48, 48, 48, 50, 32, 32, 97, 100, 100, 105, 117, 32, 36, 52, 44, 36, 48, 44, 48,
            120, 48, 48, 48, 48, 48, 48, 48, 50, 49, 50, 32, 32, 32, 32, 32, 32, 32, 108, 105, 32, 36, 97, 48, 44, 32,
            50, 10, 48, 120, 48, 48, 52, 48, 48, 48, 49, 99, 32, 32, 48, 120, 48, 48, 48, 48, 48, 48, 48, 99, 32, 32,
            115, 121, 115, 99, 97, 108, 108, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 49, 51, 32, 32,
            32, 32, 32, 32, 32, 115, 121, 115, 99, 97, 108, 108, 10, 48, 120, 48, 48, 52, 48, 48, 48, 50, 48, 32, 32,
            48, 120, 50, 52, 48, 50, 48, 48, 48, 97, 32, 32, 97, 100, 100, 105, 117, 32, 36, 50, 44, 36, 48, 44, 48,
            120, 48, 48, 48, 48, 48, 48, 48, 97, 49, 52, 32, 32, 32, 32, 32, 32, 32, 108, 105, 32, 36, 118, 48, 44, 32,
            49, 48, 10, 48, 120, 48, 48, 52, 48, 48, 48, 50, 52, 32, 32, 48, 120, 48, 48, 48, 48, 48, 48, 48, 99, 32,
            32, 115, 121, 115, 99, 97, 108, 108, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 49, 53, 32,
            32, 32, 32, 32, 32, 32, 115, 121, 115, 99, 97, 108, 108, 10
        ))
    }

    @Test
    fun testSegmentWindowDumpFormatInDataSegment() {
        if (!outFile.exists()) outFile.createNewFile()
        else outFile.writeBytes(byteArrayOf())
        Globals.initialize(false)
        for (i in Memory.dataBaseAddress..<(Memory.dataBaseAddress + 32) step 4)
            Globals.memory.setRawWord(i, 16)
        SegmentWindowDumpFormat().dumpMemoryRange(outFile, Memory.dataBaseAddress, Memory.dataBaseAddress + 32)
        val actual = outFile.readText().toCharArray().map { it.code }.toIntArray()
        assertArrayEquals(intArrayOf(
            48, 120, 49, 48, 48, 49, 48, 48, 48, 48, 32, 32, 32, 32, 48, 120, 48, 48, 48, 48, 48, 48, 49, 48, 32, 48,
            120, 48, 48, 48, 48, 48, 48, 49, 48, 32, 48, 120, 48, 48, 48, 48, 48, 48, 49, 48, 32, 48, 120, 48, 48, 48,
            48, 48, 48, 49, 48, 32, 48, 120, 48, 48, 48, 48, 48, 48, 49, 48, 32, 48, 120, 48, 48, 48, 48, 48, 48, 49,
            48, 32, 48, 120, 48, 48, 48, 48, 48, 48, 49, 48, 32, 48, 120, 48, 48, 48, 48, 48, 48, 49, 48, 32, 10
        ), actual)
    }

    @Test
    fun testSegmentWindowDumpFormatWithDefaultSettings() {
        if (!outFile.exists()) outFile.createNewFile()
        else outFile.writeBytes(byteArrayOf())
        // Default values
        Globals.initialize(false)
        for (i in Memory.dataBaseAddress..<(Memory.dataBaseAddress + 32) step 4)
            Globals.memory.setRawWord(i, 16)
        SegmentWindowDumpFormat().dumpMemoryRange(outFile, Memory.textBaseAddress, Memory.textLimitAddress + 4)
    }

    @Test
    fun testSegmentWindowDumpFormatWithIntAddresses() {
        // Int addresses, hex values
        if (!outFile.exists()) outFile.createNewFile()
        else outFile.writeBytes(byteArrayOf())
        Globals.initialize(false)
        Globals.getSettings().withMutatedBoolean(Settings.DISPLAY_ADDRESSES_IN_HEX, false) {
            for (i in Memory.dataBaseAddress..<(Memory.dataBaseAddress + 32) step 4)
                Globals.memory.setRawWord(i, 16)
            SegmentWindowDumpFormat().dumpMemoryRange(outFile, Memory.textBaseAddress, Memory.textLimitAddress + 4)
        }
    }

    @Test
    fun testSegmentWindowDumpFormatWithIntValues() {
        // Hex addresses, int values
        if (!outFile.exists()) outFile.createNewFile()
        else outFile.writeBytes(byteArrayOf())
        Globals.initialize(false)
        Globals.getSettings().withMutatedBoolean(Settings.DISPLAY_VALUES_IN_HEX, false) {
            for (i in Memory.dataBaseAddress..<(Memory.dataBaseAddress + 32) step 4)
                Globals.memory.setRawWord(i, 16)
            SegmentWindowDumpFormat().dumpMemoryRange(outFile, Memory.textBaseAddress, Memory.textLimitAddress + 4)
        }
    }

    @Test
    fun testSegmentWindowDumpFormatWithIntAddressesAndValues() {
        // Int addresses, int values
        if (!outFile.exists()) outFile.createNewFile()
        else outFile.writeBytes(byteArrayOf())
        Globals.initialize(false)
        Globals.getSettings().withMutatedBoolean(Settings.DISPLAY_ADDRESSES_IN_HEX, false) {
            Globals.getSettings().withMutatedBoolean(Settings.DISPLAY_VALUES_IN_HEX, false) {
                for (i in Memory.dataBaseAddress..<(Memory.dataBaseAddress + 32) step 4)
                    Globals.memory.setRawWord(i, 16)
                SegmentWindowDumpFormat().dumpMemoryRange(outFile, Memory.textBaseAddress, Memory.textLimitAddress + 4)
            }
        }
    }

    @Test
    fun testSegmentWindowDumpFormatTextSegmentBackwards() {
        // Create the output file if it doesn't exist, or clear its content out if it does exist
        if (!outFile.exists()) outFile.createNewFile()
        else outFile.writeBytes(byteArrayOf())
        createProgram("src/test/resources/tests/symboltable_two_labels.s")
        // Test with reversed address range
        SegmentWindowDumpFormat().dumpMemoryRange(outFile, highAddress, lowAddress)
        val empty = outFile.readText().toCharArray().map { it.code }.toIntArray()
        assertArrayEquals(intArrayOf(), empty)
        // Int address, hex values
        Globals.getSettings().withMutatedBoolean(Settings.DISPLAY_ADDRESSES_IN_HEX, false) {
            // Create the output file if it doesn't exist, or clear its content out if it does exist
            if (!outFile.exists()) outFile.createNewFile()
            else outFile.writeBytes(byteArrayOf())
            createProgram("src/test/resources/tests/symboltable_two_labels.s")
            // Test with reversed address range
            SegmentWindowDumpFormat().dumpMemoryRange(outFile, highAddress, lowAddress)
            val empty = outFile.readText().toCharArray().map { it.code }.toIntArray()
            assertArrayEquals(intArrayOf(), empty)
        }
        Globals.getSettings().withMutatedBoolean(Settings.DISPLAY_VALUES_IN_HEX, false) {
            // Create the output file if it doesn't exist, or clear its content out if it does exist
            if (!outFile.exists()) outFile.createNewFile()
            else outFile.writeBytes(byteArrayOf())
            createProgram("src/test/resources/tests/symboltable_two_labels.s")
            // Test with reversed address range
            SegmentWindowDumpFormat().dumpMemoryRange(outFile, highAddress, lowAddress)
            val empty = outFile.readText().toCharArray().map { it.code }.toIntArray()
            assertArrayEquals(intArrayOf(), empty)
        }
        Globals.getSettings().withMutatedBoolean(Settings.DISPLAY_ADDRESSES_IN_HEX, false) {
            Globals.getSettings().withMutatedBoolean(Settings.DISPLAY_VALUES_IN_HEX, false) {
                // Create the output file if it doesn't exist, or clear its content out if it does exist
                if (!outFile.exists()) outFile.createNewFile()
                else outFile.writeBytes(byteArrayOf())
                createProgram("src/test/resources/tests/symboltable_two_labels.s")
                // Test with reversed address range
                SegmentWindowDumpFormat().dumpMemoryRange(outFile, highAddress, lowAddress)
                val empty = outFile.readText().toCharArray().map { it.code }.toIntArray()
                assertArrayEquals(intArrayOf(), empty)
            }
        }
    }

    @AfterAll
    fun afterAll() {
        outFile.delete()
    }
}