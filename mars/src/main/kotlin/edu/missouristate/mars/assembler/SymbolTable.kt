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

@file:Suppress("MemberVisibilityCanBePrivate")

package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.Globals
import edu.missouristate.mars.util.Binary

/**
 * Creates a table of Symbol objects.
 *
 * @param filename The name of the file this symbol table is associated with.
 * Will be used only for output/display, so it can be any descriptive string.
 */
class SymbolTable(private val filename: String) {
    companion object {
        const val startLabel = "main"
    }

    private var table: ArrayList<Symbol> = arrayListOf()

    /**
     * Add a [Symbol] object into the array of [Symbol]s.
     *
     * @param token The token representing the [Symbol].
     * @param address The address of the token.
     * @param isData `true` if the [Symbol] is for data, `false` for text.
     * @param errors The ErrorList to add any processing errors to, should they occur.
     */
    fun addSymbol(token: Token, address: Int, isData: Boolean, errors: ErrorList) {
        val label = token.value
        if (getSymbol(label) != null) {
            errors.add(ErrorMessage(token.sourceMipsProgram, token.sourceLine, token.startPosition, "Label \"$label\" already defined!"))
        } else {
            val s = Symbol(label, address, isData)
            table.add(s)
            if (Globals.debug) println("The symbol $label with address $address has been added to the $filename symbol table.")
        }
    }

    /**
     * Removes a [Symbol] from the table.
     * If the [Symbol] isn't found, no action is taken.
     * This will rarely happen
     * (only when a variable is declared with `.globl` after already being defined in the local symbol table).
     *
     * @param token The [Token] representing the [Symbol].
     */
    fun removeSymbol(token: Token) {
        table.removeIf { it.name == token.value }
    }

    /**
     * Returns the address associated with the given label, or `null` if the address is not found.
     *
     * @param s The label.
     * @return The memory address of the label, or `null` if not found in the symbol table.
     */
    fun getAddressOrNull(s: String) = table.firstOrNull { it.name == s }?.address

    /**
     * Return the address associated with the given label.
     * Checks both the local and global tables, starting with the local table (this table) first.
     *
     * @param s The label.
     * @return The memory of the given label, or `null` if not found in either the local or global symbol tables.
     */
    fun getLocalOrGlobalAddressOrNull(s: String) = getAddressOrNull(s) ?: Globals.symbolTable.getAddressOrNull(s)

    /**
     * Get the [Symbol] object from the symbol table by name.
     *
     * @param s The target symbol name.
     * @return The Symbol object for the request target, or `null` if not found in the table.
     */
    fun getSymbol(s: String) = table.firstOrNull { it.name == s }

    /**
     * Get the [Symbol] object from the symbol table from its address.
     *
     * @param s The address.
     * @return The Symbol object with the requested address, or `null` if not found in the table.
     */
    @JvmName("getSymbolGivenAddress")
    fun getSymbolByAddress(s: String) = table.firstOrNull {
        try {
            it.address == Binary.stringToInt(s)
        } catch (e: NumberFormatException) { return@firstOrNull false }
    }

    /**
     * Get the [Symbol] object from the symbol table by its address.
     * Checks both the local and global tables, starting with the local table (this table) first.
     *
     * @param s The address.
     * @return The Symbol object with the requested address, or `null` if not found in either the local or global
     * tables.
     */
    @JvmName("getSymbolGivenAddressLocalOrGlobal")
    fun getLocalOrGlobalSymbolByAddress(s: String) =
        getSymbolByAddress(s) ?: Globals.symbolTable.getSymbolByAddress(s)

    /**
     * Get all data symbols from the local table.
     *
     * @return An ArrayList of [Symbol] objects that contain data.
     */
    fun getDataSymbols() = arrayListOf(*table.filter { it.isData }.toTypedArray())

    /**
     * Get all text symbols from the local table.
     *
     * @return An ArrayList of [Symbol] objects that contain text.
     */
    fun getTextSymbols() = arrayListOf(*table.filter { !it.isData }.toTypedArray())

    /**
     * Get all symbols.
     */
    fun getAllSymbols() = arrayListOf(*table.toTypedArray())

    /**
     * Get the number of entries currently in the table.
     *
     * @return The number of symbol table entries.
     */
    fun getSize() = table.size

    /**
     * Clear the symbol table.
     */
    fun clear() = table.clear()

    /**
     * Fix an address in a symbol table entry.
     * Any and all entries that match the original address will be modified to contain the replacement address.
     * There is no effect if none of the addresses match.
     *
     * @param originalAddress The address associated with zero or more symbol table entries.
     * @param replacementAddress The new address to be set for matching symbol table entries.
     */
    fun fixSymbolTableAddress(originalAddress: Int, replacementAddress: Int) {
        var label = getSymbolByAddress(originalAddress.toString())
        while (label != null) {
            label.address = replacementAddress
            label = getSymbolByAddress(originalAddress.toString())
        }
    }
}