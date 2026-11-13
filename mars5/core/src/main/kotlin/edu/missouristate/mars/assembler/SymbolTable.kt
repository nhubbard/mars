package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.AssemblySymbolMap.Kind
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.util.Binary

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** One assembly's label definitions with an optional enclosing global scope. */
open class SymbolTable @JvmOverloads constructor(private val globalSymbols: SymbolTable? = null) {
    private var table = ArrayList<Symbol>()

    open fun loadDefinitions(definitions: Map<String, Definition>) {
        clear()
        definitions.forEach { (name, definition) ->
            table.add(Symbol(name, definition.address, definition.kind, definition.origin, definition.macroPrivate, definition.column))
        }
    }

    open fun addSymbol(token: Token, address: Int, data: Boolean, errors: ErrorList) {
        addSymbol(token, address, if (data) Kind.DATA else Kind.TEXT, null, false, errors)
    }

    fun addSymbol(token: Token, address: Int, kind: Kind, origin: Origin?, macroPrivate: Boolean, errors: ErrorList) {
        addSymbol(token, Symbol(token.value, address, kind, origin, macroPrivate, token.writtenOrigin().originalStartPos), errors)
    }

    fun addSymbol(token: Token, symbol: Symbol, errors: ErrorList) {
        val name = token.value
        if (getSymbol(name) != null) {
            errors.add(ErrorMessage(token, "label \"$name\" already defined"))
        } else {
            table.add(symbol)
        }
    }

    open fun removeSymbol(token: Token) {
        val index = table.indexOfFirst { it.name == token.value }
        if (index >= 0) table.removeAt(index)
    }

    open fun getAddress(name: String?): Int = table.firstOrNull { it.name == name }?.address ?: NOT_FOUND

    open fun getAddressLocalOrGlobal(name: String?): Int = getSymbolLocalOrGlobal(name)?.address ?: NOT_FOUND

    open fun getSymbolLocalOrGlobal(name: String?): Symbol? {
        val local = getSymbol(name)
        return if (globalSymbols != null && (local == null || local.address == NOT_FOUND)) globalSymbols.getSymbol(name) else local
    }

    open fun getSymbol(name: String?): Symbol? = table.firstOrNull { it.name == name }

    open fun getSymbolGivenAddress(spelling: String): Symbol? {
        val address = try {
            Binary.stringToInt(spelling)
        } catch (_: NumberFormatException) {
            return null
        }
        return table.firstOrNull { it.address == address }
    }

    open fun getSymbolGivenAddressLocalOrGlobal(spelling: String): Symbol? =
        getSymbolGivenAddress(spelling) ?: globalSymbols?.getSymbolGivenAddress(spelling)

    open val dataSymbols: ArrayList<Symbol> get() = table.filterTo(ArrayList()) { it.type }

    open val textSymbols: ArrayList<Symbol> get() = table.filterTo(ArrayList()) { !it.type }

    open val allSymbols: ArrayList<Symbol> get() = ArrayList(table)

    open val size: Int get() = table.size

    open fun clear() {
        table = ArrayList()
    }

    open fun fixSymbolTableAddress(originalAddress: Int, replacementAddress: Int) {
        for (symbol in table) {
            if (symbol.address == originalAddress) symbol.address = replacementAddress
        }
    }

    companion object {
        const val NOT_FOUND = -1

        // Java and Kotlin callers share the same reserved entry label.
        @Suppress("SameReturnValue")
        @JvmStatic
        fun getStartLabel(): String = "main"
    }
}
