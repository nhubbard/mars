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

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.Globals
import edu.missouristate.mars.MIPSProgram
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Memory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension::class)
class TestDataSegmentForwardReferences {
    private lateinit var instance: Assembler.DataSegmentForwardReferences

    @BeforeEach
    fun reset() {
        instance = Assembler.DataSegmentForwardReferences()
    }

    @Test
    fun testInitialSize() {
        assertEquals(0, instance.size())
    }

    @Test
    fun testAdd() {
        instance.add(0x1010, 1, Token(TokenTypes.IDENTIFIER, "main", MIPSProgram(), 0, 0))
        assertEquals(1, instance.size())
    }

    @Test
    fun testAddAnother() {
        instance.add(0x1010, 1, Token(TokenTypes.IDENTIFIER, "main", MIPSProgram(), 0, 0))
        assertEquals(1, instance.size())
        val instance2 = Assembler.DataSegmentForwardReferences()
        instance2.add(0x1011, 1, Token(TokenTypes.IDENTIFIER, "main2", MIPSProgram(), 1, 0))
        instance.add(instance2)
        assertEquals(2, instance.size())
    }

    @Test
    fun testClear() {
        instance.add(0x1010, 1, Token(TokenTypes.IDENTIFIER, "main", MIPSProgram(), 0, 0))
        instance.add(0x1011, 1, Token(TokenTypes.IDENTIFIER, "main", MIPSProgram(), 1, 0))
        assertEquals(2, instance.size())
        instance.clear()
        assertEquals(0, instance.size())
    }

    @Test
    fun testResolveSuccess() {
        // Mock SymbolTable and Memory
        val symbolTable = mock(SymbolTable::class.java)
        val memory = mock(Memory::class.java)
        `when`(symbolTable.getLocalOrGlobalAddressOrNull(anyString())).thenReturn(0x1000)
        `when`(memory.set(anyInt(), anyInt(), anyInt())).thenReturn(0)
        // Set global memory to mock instance
        Globals.memory = memory
        // Add a reference
        instance.add(0x1000, 1, Token(TokenTypes.IDENTIFIER, "main", MIPSProgram(), 0, 0))
        // Call resolve
        val count = instance.resolve(symbolTable)
        assertEquals(1, count)
        // Reset memory afterward to avoid messing up other tests
        Globals.resetInitialized()
        Globals.initialize()
    }

    @Test
    fun testResolveNotFound() {
        // Mock SymbolTable
        val symbolTable = mock(SymbolTable::class.java)
        `when`(symbolTable.getLocalOrGlobalAddressOrNull(anyString())).thenReturn(null)
        // Add a reference
        instance.add(0x1000, 1, Token(TokenTypes.IDENTIFIER, "main", MIPSProgram(), 0, 0))
        // Call resolve
        val count = instance.resolve(symbolTable)
        assertEquals(0, count)
    }

    @Test
    fun testResolveBadMemoryIgnored() {
        // Mock SymbolTable and Memory
        val symbolTable = mock(SymbolTable::class.java)
        val memory = mock(Memory::class.java)
        `when`(symbolTable.getLocalOrGlobalAddressOrNull(anyString())).thenReturn(0x1000)
        `when`(memory.set(anyInt(), anyInt(), anyInt())).thenThrow(AddressErrorException::class.java)
        // Set global memory to mock instance
        Globals.memory = memory
        // Add a reference
        instance.add(0x1000, 1, Token(TokenTypes.IDENTIFIER, "main", MIPSProgram(), 0, 0))
        // Call resolve
        val count = instance.resolve(symbolTable)
        assertEquals(1, count)
        // Reset memory afterward to prevent breaking other tests
        Globals.resetInitialized()
        Globals.initialize()
    }

    @Test
    fun testGenerateErrorMessages() {
        instance.add(0x1000, 1, Token(TokenTypes.IDENTIFIER, "main", MIPSProgram(), 0, 0))
        instance.add(0x1010, 1, Token(TokenTypes.IDENTIFIER, "error", MIPSProgram(), 1, 0))
        val errors = ErrorList()
        instance.generateErrorMessages(errors)
        assertEquals(2, errors.errorCount)
        val messages = errors.messages
        assertEquals(0, messages[0].line)
        assertEquals(0, messages[0].position)
        assertEquals("Symbol \"main\" not found in symbol table.", messages[0].message)
        assertEquals(1, messages[1].line)
        assertEquals(0, messages[1].position)
        assertEquals("Symbol \"error\" not found in symbol table.", messages[1].message)
    }
}