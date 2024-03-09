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
class TestDumpFormatLoader {
    private val instance = DumpFormatLoader()

    @Test
    fun testLoadDumpFormat() {
        // First time creates a non-null list
        val formats = instance.loadDumpFormats()
        assertEquals(formats.size, 6)
        // Second time just returns the non-null cached list
        val formats2 = instance.loadDumpFormats()
        assertEquals(formats2.size, 6)
    }

    @ParameterizedTest
    @MethodSource("findProvider")
    fun testFindDumpFormatGivenCommandDescriptor(descriptor: String, expected: DumpFormat?) {
        val formatList = instance.loadDumpFormats()
        val dumpFormat = DumpFormatLoader.findDumpFormatGivenCommandDescriptor(formatList, descriptor)
        println(dumpFormat.toString())
        assertEquals(expected?.commandDescriptor, dumpFormat?.commandDescriptor)
    }

    companion object {
        @JvmStatic
        fun findProvider(): Stream<Arguments> = twoArgumentsOf(
            "Binary" to BinaryDumpFormat(),
            "BinaryText" to BinaryTextDumpFormat(),
            "HexText" to HexTextDumpFormat(),
            "HEX" to IntelHexDumpFormat(),
            "dfoiaspodfiunasioudf" to null
        )
    }
}