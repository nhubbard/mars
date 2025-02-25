/*
 * Copyright (c) 2003-2025, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2025-present, Nicholas Hubbard
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Paths
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestSegmentWindowDumpFormat {
    @BeforeAll
    fun setUp() {
        Globals.initialize(false)
        val base = Memory.dataSegmentBaseAddress
        val limit = Memory.dataSegmentLimitAddress - Memory.WORD_LENGTH_BYTES
        val stepBytes = Memory.WORD_LENGTH_BYTES
        for (address in base..<limit step stepBytes)
            Globals.memory.setWord(address, address - Memory.dataSegmentBaseAddress)
        Globals.getSettings().setBooleanSetting(Settings.SELF_MODIFYING_CODE_ENABLED, true)
        val textBase = Memory.textBaseAddress
        val textLimit = Memory.textLimitAddress - Memory.WORD_LENGTH_BYTES
        for (address in textBase..<textLimit step stepBytes)
            Globals.memory.setRawWord(address, address - Memory.textBaseAddress)
        Globals.getSettings().setBooleanSetting(Settings.SELF_MODIFYING_CODE_ENABLED, false)
    }

    @AfterAll
    fun tearDown() {
        Globals.resetInitialized()
    }

    @Test
    fun testSegmentWindowDumpFormat() {
        val format = SegmentWindowDumpFormat()
        val outputFile = createTempFile(Paths.get(System.getProperty("java.io.tmpdir"))).toFile()
        println(outputFile.absolutePath)
        format.dumpMemoryRange(
            outputFile,
            Memory.dataSegmentBaseAddress,
            Memory.dataSegmentLimitAddress - Memory.WORD_LENGTH_BYTES
        )
        val expected = Paths.get("src/test/resources/expected_values/dumpformats_segmentwindow_datasegment.txt").toFile()
        assertEquals(expected.readLines(), outputFile.readLines())
    }

    @Test
    fun testSegmentWindowDumpFormat_withoutHexAddresses() {
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX, false)
        val format = SegmentWindowDumpFormat()
        val outputFile = createTempFile(Paths.get(System.getProperty("java.io.tmpdir"))).toFile()
        println(outputFile.absolutePath)
        format.dumpMemoryRange(
            outputFile,
            Memory.dataSegmentBaseAddress,
            Memory.dataSegmentLimitAddress - Memory.WORD_LENGTH_BYTES
        )
        val expected = Paths.get("src/test/resources/expected_values/dumpformats_segmentwindow_datasegment_intaddresses.txt").toFile()
        assertEquals(expected.readLines(), outputFile.readLines())
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX, true)
    }

    @Test
    fun testSegmentWindowDumpFormat_withoutHexValues() {
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX, false)
        val format = SegmentWindowDumpFormat()
        val outputFile = createTempFile(Paths.get(System.getProperty("java.io.tmpdir"))).toFile()
        println(outputFile.absolutePath)
        format.dumpMemoryRange(
            outputFile,
            Memory.dataSegmentBaseAddress,
            Memory.dataSegmentLimitAddress - Memory.WORD_LENGTH_BYTES
        )
        val expected = Paths.get("src/test/resources/expected_values/dumpformats_segmentwindow_datasegment_intvalues.txt").toFile()
        assertEquals(expected.readLines(), outputFile.readLines())
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX, true)
    }

    @Test
    fun testSegmentWindowDumpFormat_withInvalidAddress() {
        val format = SegmentWindowDumpFormat()
        val outputFile = createTempFile(Paths.get(System.getProperty("java.io.tmpdir"))).toFile()
        assertFailsWith<AddressErrorException> {
            format.dumpMemoryRange(
                outputFile,
                Memory.dataSegmentBaseAddress,
                Memory.dataSegmentLimitAddress
            )
        }
    }

    @Test
    fun testSegmentWindowDumpFormat_inTextSegment() {
        val format = SegmentWindowDumpFormat()
        val outputFile = createTempFile(Paths.get(System.getProperty("java.io.tmpdir"))).toFile()
        println(outputFile.absolutePath)
        format.dumpMemoryRange(
            outputFile,
            Memory.textBaseAddress,
            Memory.textLimitAddress - Memory.WORD_LENGTH_BYTES
        )
        val expected = Paths.get("src/test/resources/expected_values/dumpformats_segmentwindow_textsegment.txt").toFile()
        assertEquals(expected.readLines(), outputFile.readLines())
    }

    @Test
    fun testSegmentWindowDumpFormat_outsideTextAndDataSegments() {
        val format = SegmentWindowDumpFormat()
        val outputFile = createTempFile(Paths.get(System.getProperty("java.io.tmpdir"))).toFile()
        format.dumpMemoryRange(
            outputFile,
            Memory.kernelTextBaseAddress,
            Memory.kernelTextLimitAddress - Memory.WORD_LENGTH_BYTES
        )
        val expected = emptyList<String>()
        assertEquals(expected, outputFile.readLines())
    }
}