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
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.Settings
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.util.Binary
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import kotlin.test.assertContains

class NewTestSegmentWindowDumpFormat {
    private lateinit var dumpFormat: SegmentWindowDumpFormat
    private val mockSettings: Settings = mock()
    private val mockMemory: Memory = mock()

    @BeforeEach
    fun setUp() {
        dumpFormat = SegmentWindowDumpFormat()
        Mockito.mockStatic<Globals>(Globals::class.java).use {
            `when`(Globals.getSettings()).thenReturn(mockSettings)
            `when`(Globals.getMemory()).thenReturn(mockMemory)
        }
    }

    @Test
    fun testDumpForTextSegment(@TempDir tempDir: File) {
        val testFile = File(tempDir, "dump.txt")
        val address = 0x00400000
        val instruction = 0x8C100004
        val mockStatement = mock<ProgramStatement>()

        `when`(mockSettings.getBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX)).thenReturn(true)
        `when`(mockSettings.getBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX)).thenReturn(true)
        `when`(mockMemory.getRawWordOrNull(address)).thenReturn(instruction.toInt())
        `when`(mockMemory.getStatement(address)).thenReturn(mockStatement)
        `when`(mockStatement.getPrintableBasicAssemblyStatement()).thenReturn("lw \$s0, 4(\$zero)")
        `when`(mockStatement.getSource()).thenReturn("lw \$s0, 4(\$zero)")
        `when`(mockStatement.getSourceLine()).thenReturn(10)

        dumpFormat.dumpMemoryRange(testFile, address, address)

        val content = testFile.readText()
        assertContains(content, Binary.intToHexString(address))
        assertContains(content, Binary.intToHexString(instruction.toInt()))
        assertContains(content, "lw \$s0, 4(\$zero)")
    }
}