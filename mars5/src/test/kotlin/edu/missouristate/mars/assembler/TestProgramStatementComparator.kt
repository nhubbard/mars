/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
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

package edu.missouristate.mars.assembler

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.threeArgumentsOf
import edu.missouristate.mars.assembler.Assembler.ProgramStatementComparator
import edu.missouristate.mars.tri
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension::class)
class TestProgramStatementComparator {
    @Test
    fun testCompareNulls() {
        assertThrows<ClassCastException> {
            ProgramStatementComparator().compare(null, null)
        }
    }

    @Test
    fun testCompareNotNullToNull() {
        val ps = mock(ProgramStatement::class.java)
        assertThrows<ClassCastException> {
            ProgramStatementComparator().compare(ps, null)
        }
    }

    @Test
    fun testCompareNullToNotNull() {
        val ps = mock(ProgramStatement::class.java)
        assertThrows<ClassCastException> {
            ProgramStatementComparator().compare(null, ps)
        }
    }

    @ParameterizedTest
    @MethodSource("compareSource")
    fun testActualCompare(leftAddress: Int, rightAddress: Int, expectedResult: Int) {
        val left = mock(ProgramStatement::class.java)
        val right = mock(ProgramStatement::class.java)
        `when`(left.getAddress()).thenReturn(leftAddress)
        `when`(right.getAddress()).thenReturn(rightAddress)
        assertEquals(expectedResult, ProgramStatementComparator().compare(left, right))
    }

    companion object {
        @JvmStatic
        fun compareSource(): Stream<Arguments> = threeArgumentsOf(
            (10 to 20) tri -10,
            (-10 to -20) tri 10,
            (10 to -20) tri -20,
            (-10 to 20) tri 20,
            (0 to 10) tri -10,
            (10 to 0) tri 10,
            (-10 to 0) tri 0,
            (0 to -10) tri -10,
            (0 to 0) tri 0
        )
    }
}