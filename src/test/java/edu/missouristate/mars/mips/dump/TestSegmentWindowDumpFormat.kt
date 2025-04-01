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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestSegmentWindowDumpFormat : BaseDumpFormatTest() {
    private var tempFiles = mutableListOf<File>()
    
    @BeforeAll
    fun setUp() {
        setUpBase()
    }

    @AfterAll
    fun tearDown() {
        tearDownBase()
        // Clean up temp files
        tempFiles.forEach { it.delete() }
    }

    private fun getTempFile(): File {
        val file = DumpUtils.getTempFile()
        tempFiles.add(file)
        return file
    }

    @Test
    fun testSegmentWindowDumpFormat() {
        val format = SegmentWindowDumpFormat()
        val outputFile = getTempFile()
        format.dumpMemoryRange(outputFile, dataBase, dataLimit)
        val expected = DumpUtils.decompressTestData("segmentwindow", "datasegment.gz")
        assertEquals(expected, outputFile.readText())
    }

    @Test
    fun testSegmentWindowDumpFormat_withoutHexAddresses() {
        val format = SegmentWindowDumpFormat()
        val outputFile = getTempFile()
        
        // Set hex addresses to false
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX, false)
        
        try {
            format.dumpMemoryRange(outputFile, dataBase, dataLimit)
            val expected = DumpUtils.decompressTestData("segmentwindow", "datasegment_intaddresses.gz")
            assertEquals(expected, outputFile.readText())
        } finally {
            // Restore hex addresses setting
            Globals.getSettings().setBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX, true)
        }
    }

    @Test
    fun testSegmentWindowDumpFormat_withoutHexValues() {
        val format = SegmentWindowDumpFormat()
        val outputFile = getTempFile()
        
        // Set hex values to false
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX, false)
        
        try {
            format.dumpMemoryRange(outputFile, dataBase, dataLimit)
            val expected = DumpUtils.decompressTestData("segmentwindow", "datasegment_intvalues.gz")
            assertEquals(expected, outputFile.readText())
        } finally {
            // Restore hex values setting
            Globals.getSettings().setBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX, true)
        }
    }

    @Test
    fun testSegmentWindowDumpFormat_inTextSegment() {
        val format = SegmentWindowDumpFormat()
        val outputFile = getTempFile()
        
        format.dumpMemoryRange(outputFile, textBase, textLimit)
        val expected = DumpUtils.decompressTestData("segmentwindow", "textsegment.gz")
        assertEquals(expected, outputFile.readText())
    }

    @Test
    fun testSegmentWindowDumpFormat_outsideTextAndDataSegments() {
        val format = SegmentWindowDumpFormat()
        val outputFile = getTempFile()
        
        format.dumpMemoryRange(outputFile, kernelTextBase, kernelTextLimit)
        val expected = emptyList<String>()
        assertEquals(expected, outputFile.readLines())
    }
}