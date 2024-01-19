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

package edu.missouristate.mars.assembler

enum class Directives {
    DATA(".data", "Subsequent items stored in Data segment at next available address"),
    TEXT(".text", "Subsequent items (instructions) stored in Text segment at next available address"),
    WORD(".word", "Store the listed value(s) as 32 bit words on word boundary"),
    ASCII(".ascii", "Store the string in the Data segment but do not add null terminator"),
    ASCIIZ(".asciiz", "Store the string in the Data segment and add null terminator"),
    BYTE(".byte", "Store the listed value(s) as 8 bit bytes"),
    ALIGN(".align", "Align next data item on specified byte boundary (0=byte, 1=half, 2=word, 3=double)"),
    HALF(".half", "Store the listed value(s) as 16 bit half-words on half-word boundary"),
    SPACE(".space", "Reserve the next specified number of bytes in Data segment"),
    DOUBLE(".double", "Store the listed value(s) as double precision floating point"),
    FLOAT(".float", "Store the listed value(s) as single precision floating point"),
    EXTERN(".extern", "Declare the listed label and byte length to be a global data field"),
    KDATA(".kdata", "Subsequent items stored in Kernel Data segment at next available address"),
    KTEXT(".ktext", "Subsequent items (instructions) stored in Kernel Text segment at next available address"),
    GLOBL(".globl", "Declare the listed label(s) as global to enable referencing from other files"),
    SET(".set", "Set assembler variables. Currently ignored but included for SPIM compatability."),
    EQV(".eqv", "Substitute second operand for first. First operand is symbol, second operand is expression (like #define)."),
    MACRO(".macro", "Begin macro definition. See .end_macro."),
    END_MACRO(".end_macro", "End macro definition. See .macro."),
    INCLUDE(".include", "Insert the contents of the specified file. Put filename in quotes.");

    private val descriptor: String
    private val description: String

    constructor() {
        descriptor = "generic"
        description = ""
    }

    constructor(name: String, description: String) {
        descriptor = name
        this.description = description
    }

    override fun toString(): String = descriptor
    fun getName(): String = descriptor
    fun getDescription(): String = description

    companion object {
        /**
         * Find the [Directives] object, if any, which matches the given name.
         */
        @JvmStatic
        fun matchDirective(name: String): Directives? =
            entries.firstOrNull { it.descriptor == name }

        /**
         * Find the [Directives] objects, if any, which contains the given string as a prefix.
         */
        @JvmStatic
        fun prefixMatchDirectives(prefix: String): ArrayList<Directives> =
            arrayListOf(*entries.filter { it.descriptor.lowercase().startsWith(prefix.lowercase()) }.toTypedArray())

        @JvmStatic
        fun getDirectiveList(): ArrayList<Directives> = arrayListOf(*entries.toTypedArray())

        /**
         * Determine if the given directive is for an integer (WORD, HALF, or BYTE).
         */
        @JvmStatic
        fun isIntegerDirective(directive: Directives) =
            directive in listOf(WORD, HALF, BYTE)

        /**
         * Determine if the given directive is for a floating point number (FLOAT or DOUBLE).
         */
        @JvmStatic
        fun isFloatingDirective(directive: Directives) =
            directive in listOf(FLOAT, DOUBLE)
    }
}