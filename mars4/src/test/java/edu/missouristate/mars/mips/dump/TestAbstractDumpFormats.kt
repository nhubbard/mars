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

import edu.missouristate.mars.twoArgumentsOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestAbstractDumpFormats {
    @ParameterizedTest
    @MethodSource("nameProvider")
    fun testName(format: AbstractDumpFormat, name: String) {
        assertEquals(format.toString(), name)
    }

    @ParameterizedTest
    @MethodSource("descriptorProvider")
    fun testDescriptor(format: AbstractDumpFormat, descriptor: String?) {
        assertEquals(format.commandDescriptor, descriptor)
    }

    @ParameterizedTest
    @MethodSource("descriptionProvider")
    fun testDescription(format: AbstractDumpFormat, description: String) {
        assertEquals(format.description, description)
    }

    @ParameterizedTest
    @MethodSource("extensionProvider")
    fun testExtension(format: AbstractDumpFormat, extension: String) {
        assertEquals(format.fileExtension, extension)
    }

    companion object {
        @JvmStatic
        fun nameProvider(): Stream<Arguments> = twoArgumentsOf(
            AsciiTextDumpFormat() to "ASCII Text",
            BinaryDumpFormat() to "Binary",
            BinaryTextDumpFormat() to "Binary Text",
            HexTextDumpFormat() to "Hexadecimal Text",
            IntelHexDumpFormat() to "Intel hex format",
            SegmentWindowDumpFormat() to "Text/Data Segment Window"
        )

        @JvmStatic
        fun descriptorProvider(): Stream<Arguments> = twoArgumentsOf(
            AsciiTextDumpFormat() to "AsciiText",
            BinaryDumpFormat() to "Binary",
            BinaryTextDumpFormat() to "BinaryText",
            HexTextDumpFormat() to "HexText",
            IntelHexDumpFormat() to "HEX",
            SegmentWindowDumpFormat() to null
        )

        @JvmStatic
        fun descriptionProvider(): Stream<Arguments> = twoArgumentsOf(
            AsciiTextDumpFormat() to "Memory contents interpreted as ASCII characters",
            BinaryDumpFormat() to "Written as byte stream to binary file",
            BinaryTextDumpFormat() to "Written as '0' and '1' characters to text file",
            HexTextDumpFormat() to "Written as hex characters to text file",
            IntelHexDumpFormat() to "Written as Intel Hex Memory File",
            SegmentWindowDumpFormat() to " Text Segment Window or Data Segment Window format to text file"
        )

        @JvmStatic
        fun extensionProvider(): Stream<Arguments> = twoArgumentsOf(
            AsciiTextDumpFormat() to "txt",
            BinaryDumpFormat() to "bin",
            BinaryTextDumpFormat() to "txt",
            HexTextDumpFormat() to "txt",
            IntelHexDumpFormat() to "hex",
            SegmentWindowDumpFormat() to "txt"
        )
    }
}