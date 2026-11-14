package edu.missouristate.mars.assembler

/*
Copyright (c) 2003-2012,  Pete Sanderson and Kenneth Vollmar

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

/** Names, descriptions, and matching rules for the assembler directives. */
class Directives private constructor(val name: String, val description: String) {
    override fun toString(): String = name

    companion object {
        private val directiveList = ArrayList<Directives>()

        @JvmField val DATA = directive(".data", "Subsequent items stored in Data segment at next available address")
        @JvmField val TEXT = directive(".text", "Subsequent items (instructions) stored in Text segment at next available address")
        @JvmField val WORD = directive(".word", "Store the listed value(s) as 32 bit words on word boundary")
        @JvmField val DWORD = directive(".dword", "Store aligned 64-bit integer values in MARS64")
        @JvmField val ASCII = directive(".ascii", "Store the string in the Data segment but do not add null terminator")
        @JvmField val ASCIIZ = directive(".asciiz", "Store the string in the Data segment and add null terminator")
        @JvmField val UTF8 = directive(".utf8", "Store a string as UTF-8 bytes")
        @JvmField val UTF8Z = directive(".utf8z", "Store a string as UTF-8 bytes followed by a zero byte")
        @JvmField val BYTE = directive(".byte", "Store the listed value(s) as 8 bit bytes")
        @JvmField val ALIGN = directive(".align", "Align next data item on specified byte boundary (0=byte, 1=half, 2=word, 3=double)")
        @JvmField val P2ALIGN = directive(".p2align", "Align code or data to a power-of-two boundary in MARS64")
        @JvmField val BALIGN = directive(".balign", "Align code or data to a byte boundary in MARS64")
        @JvmField val HALF = directive(".half", "Store the listed value(s) as 16 bit halfwords on halfword boundary")
        @JvmField val SPACE = directive(".space", "Reserve the next specified number of bytes in Data segment")
        @JvmField val DOUBLE = directive(".double", "Store the listed value(s) as double precision floating point")
        @JvmField val FLOAT = directive(".float", "Store the listed value(s) as single precision floating point")
        @JvmField val EXTERN = directive(".extern", "Declare the listed label and byte length to be a global data field")
        @JvmField val KDATA = directive(".kdata", "Subsequent items stored in Kernel Data segment at next available address")
        @JvmField val KTEXT = directive(".ktext", "Subsequent items (instructions) stored in Kernel Text segment at next available address")
        @JvmField val GLOBL = directive(".globl", "Declare the listed label(s) as global to enable referencing from other files")
        @JvmField val SET = directive(".set", "Set assembler variables.  Currently ignored but included for SPIM compatability")
        @JvmField val EQV = directive(".eqv", "Substitute second operand for first. First operand is symbol, second operand is expression (like #define)")
        @JvmField val MACRO = directive(".macro", "Begin macro definition.  See .end_macro")
        @JvmField val END_MACRO = directive(".end_macro", "End macro definition.  See .macro")
        @JvmField val INCLUDE = directive(".include", "Insert the contents of the specified file.  Put filename in quotes.")
        @JvmField val SECTION = directive(".section", "Select a text or data section from freestanding MARS64 assembler source")
        @JvmField val NAN = directive(".nan", "Select the Release 6 NaN encoding with .nan 2008")

        internal val compilerMetadata = listOf(".file", ".type", ".ent", ".frame", ".mask", ".fmask", ".end", ".size", ".ident", ".addrsig")
            .map { directive(it, "Compiler metadata accepted by the MARS64 assembler") }.toSet()

        private val objectAttributes = setOf("@function", "@object", "@progbits", "@nobits")
        private val aliases = mapOf(
            ".global" to GLOBL, ".2byte" to HALF, ".short" to HALF, ".4byte" to WORD, ".long" to WORD,
            ".8byte" to DWORD, ".quad" to DWORD, ".asciz" to ASCIIZ, ".string" to ASCIIZ,
        )

        fun isObjectAttribute(value: String): Boolean = value in objectAttributes

        fun sectionSegment(name: String): Directives? {
            val section = name.removeSurrounding("\"")
            if (!section.startsWith('.')) return null
            return when (section.drop(1).substringBefore('.')) {
                "text" -> TEXT
                "data", "rodata", "rdata", "bss", "sdata", "sbss" -> DATA
                else -> null
            }
        }

        private fun directive(name: String, description: String) =
            Directives(name, description).also(directiveList::add)

        @JvmStatic
        fun matchDirective(text: String): Directives? =
            aliases[text.lowercase()] ?: directiveList.firstOrNull { text.equals(it.name, ignoreCase = true) }

        @JvmStatic
        fun getDirectiveList(): ArrayList<Directives> = directiveList

        @JvmStatic
        fun isIntegerDirective(directive: Directives?): Boolean =
            directive == WORD || directive == HALF || directive == BYTE

        @JvmStatic
        fun isFloatingDirective(directive: Directives?): Boolean = directive == FLOAT || directive == DOUBLE
    }
}
