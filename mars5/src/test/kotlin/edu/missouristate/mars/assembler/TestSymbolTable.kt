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

import edu.missouristate.mars.*
import edu.missouristate.mars.mips.hardware.Memory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestSymbolTable {
    @Test
    fun testAddSymbolDoesNotAlreadyExist() {
        val (program, errors) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        val mainAddress = table.getAddressOrNull("main") ?: fail()
        // No debug
        table.addSymbol(
            Token(TokenTypes.IDENTIFIER, "main2", program, 17, 0),
            mainAddress,
            false,
            errors
        )
        // Yes debug
        Globals.debug = true
        val output = tapSystemOut {
            table.addSymbol(
                Token(TokenTypes.IDENTIFIER, "main3", program, 18, 0),
                mainAddress,
                false,
                errors
            )
        }
        // Partial string match only because I don't know the symbol table name
        assertTrue(output.contains("The symbol main3 with address $mainAddress has been added to the"))
        Globals.debug = false
    }

    @Test
    fun testAddSymbolAlreadyExists() {
        val (program, errors) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        val mainAddress = table.getAddressOrNull("main") ?: fail()
        table.addSymbol(
            Token(TokenTypes.IDENTIFIER, "main", program, 15, 0),
            mainAddress,
            false,
            errors
        )
        assertTrue(errors.hasErrors)
        val error = errors.messages[0]
        assertEquals(15, error.line)
        assertEquals(0, error.position)
        assertEquals("label \"main\" already defined", error.message)
    }

    @Test
    fun testRemoveSymbolExistsNoDebug() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        table.removeSymbol(Token(TokenTypes.IDENTIFIER, "main", program, 0, 0))
        assertEquals(null, table.getSymbol("main"))
    }

    @Test
    fun testRemoveSymbolExistsDebug() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        Globals.debug = true
        val output = tapSystemOut {
            table.removeSymbol(Token(TokenTypes.IDENTIFIER, "main", program, 0, 0))
        }
        // Partial string match only because I don't know the symbol table name
        assertTrue(output.contains("The symbol main has been removed from the"))
        Globals.debug = false
        assertEquals(null, table.getSymbol("main"))
    }

    @Test
    fun testRemoveSymbolSecondSymbol() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        table.removeSymbol(Token(TokenTypes.IDENTIFIER, "print_one", program, 3, 0))
        assertEquals(null, table.getSymbol("print_one"))
    }

    @Test
    fun testRemoveSymbolNotFound() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        table.removeSymbol(Token(TokenTypes.IDENTIFIER, "main2", program, 0, 0))
    }

    @Test
    fun testGetAddress() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        val baseAddress = Memory.textBaseAddress
        assertEquals(0, (table.getAddressOrNull("main") ?: fail()) - baseAddress)
        assertEquals(4, (table.getAddressOrNull("print_one") ?: fail()) - baseAddress)
        assertEquals(20, (table.getAddressOrNull("print_two") ?: fail()) - baseAddress)
    }

    @Test
    fun textGetAddressNotFound() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test_no_args.s")
        val table = program.getLocalSymbolTable()
        assertNull(table.getAddressOrNull("main"))
    }

    @Test
    fun testGetAddressLocalOrGlobalHasLocalValue() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        val baseAddress = Memory.textBaseAddress
        assertEquals(0, (table.getLocalOrGlobalAddressOrNull("main") ?: fail()) - baseAddress)
        assertEquals(4, (table.getLocalOrGlobalAddressOrNull("print_one") ?: fail()) - baseAddress)
        assertEquals(20, (table.getLocalOrGlobalAddressOrNull("print_two") ?: fail()) - baseAddress)
    }

    @Test
    fun testGetAddressLocalOrGlobalHasGlobalValue() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_global_label.s")
        val table = program.getLocalSymbolTable()
        assertEquals(0, (table.getLocalOrGlobalAddressOrNull("globalVar") ?: fail()) - Memory.dataBaseAddress)
    }

    @Test
    fun getSymbolGivenAddressValid() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        val strAddress = (table.getAddressOrNull("main") ?: fail()).toString()
        val sym = table.getLocalOrGlobalSymbolByAddress(strAddress)
        assertEquals(table.getAddressOrNull("main"), sym?.address)
        assertEquals("main", sym?.name)
        assertFalse(sym?.isData ?: true)
    }

    @Test
    fun getSymbolGivenAddressInvalidAddressValue() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        assertNull(table.getLocalOrGlobalSymbolByAddress("g"))
    }

    @Test
    fun getSymbolGivenAddressNonExistentAddress() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_two_labels.s")
        val table = program.getLocalSymbolTable()
        assertNull(table.getLocalOrGlobalSymbolByAddress("12345678"))
    }

    @Test
    fun getSymbolGivenAddressLocalOrGlobalValid() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_global_label.s")
        val table = program.getLocalSymbolTable()
        val address = table.getLocalOrGlobalAddressOrNull("globalVar")
        val sym = table.getLocalOrGlobalSymbolByAddress(address.toString())
        assertEquals(address, sym?.address)
        assertEquals("globalVar", sym?.name)
        assertTrue(sym?.isData ?: false)
    }

    @Test
    fun getDataSymbols() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_data_symbols.s")
        val table = program.getLocalSymbolTable()
        val dataSymbols = table.getDataSymbols()
        assertEquals(2, dataSymbols.size)
        assertEquals(2, table.getSize())
    }

    @Test
    fun getDataSymbolsOnlyTextSymbols() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_text_symbols.s")
        val table = program.getLocalSymbolTable()
        val dataSymbols = table.getDataSymbols()
        assertEquals(0, dataSymbols.size)
        assertEquals(2, table.getSize())
    }

    @Test
    fun getTextSymbols() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_text_symbols.s")
        val table = program.getLocalSymbolTable()
        val textSymbols = table.getTextSymbols()
        assertEquals(2, textSymbols.size)
        assertEquals(2, table.getSize())
    }

    @Test
    fun getTextSymbolsOnlyDataSymbols() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_data_symbols.s")
        val table = program.getLocalSymbolTable()
        val textSymbols = table.getTextSymbols()
        assertEquals(0, textSymbols.size)
        assertEquals(2, table.getSize())
    }

    @Test
    fun getAllSymbols() {
        val (program, _) = createProgram("src/test/resources/tests/symboltable_both_symbols.s")
        val table = program.getLocalSymbolTable()
        val symbols = table.getAllSymbols()
        assertEquals(4, symbols.size)
    }

    @Test
    fun testFixSymbolTableAddress() {
        val table = SymbolTable("testFile")
        val originalAddress = 100
        val replacementAddress = 200

        table.addSymbol(
            Token(TokenTypes.IDENTIFIER, "label1", MIPSProgram(), 0, 0),
            originalAddress, true, ErrorList()
        )
        table.addSymbol(
            Token(TokenTypes.IDENTIFIER, "label2", MIPSProgram(), 1, 0),
            150, false, ErrorList()
        )
        table.addSymbol(
            Token(TokenTypes.IDENTIFIER, "label3", MIPSProgram(), 2, 0),
            originalAddress, true, ErrorList()
        )

        table.fixSymbolTableAddress(originalAddress, replacementAddress)

        assertEquals(replacementAddress, table.getAddressOrNull("label1"))
        assertEquals(150, table.getAddressOrNull("label2"))
        assertEquals(replacementAddress, table.getAddressOrNull("label3"))
    }

    @Test
    fun testStartLabel() {
        assertEquals("main", SymbolTable.startLabel)
    }
}