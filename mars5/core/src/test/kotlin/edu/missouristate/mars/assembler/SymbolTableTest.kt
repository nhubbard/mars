package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SymbolTableTest {
    @Test
    fun `relocation updates every alias and an unchanged address is safe`() {
        val symbols = SymbolTable()
        val errors = ErrorList()
        val original = 0x10010000
        val replacement = original + 4
        for (name in listOf("first", "second")) {
            symbols.addSymbol(Token(TokenTypes.IDENTIFIER, name, null, 1, 1), original, true, errors)
        }

        symbols.fixSymbolTableAddress(original, original)
        assertEquals(listOf(original, original), symbols.allSymbols.map(Symbol::address))

        symbols.fixSymbolTableAddress(original, replacement)
        assertEquals(listOf(replacement, replacement), symbols.allSymbols.map(Symbol::address))
        assertEquals(0, errors.errorCount())
    }
}
