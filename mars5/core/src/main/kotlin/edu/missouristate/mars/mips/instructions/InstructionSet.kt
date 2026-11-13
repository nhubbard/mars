package edu.missouristate.mars.mips.instructions

/*
Copyright (c) 2003-2013,  Pete Sanderson and Kenneth Vollmar

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

/** Ordered instruction forms and their binary decoding tables. */
open class InstructionSet {
    private val forms = ArrayList<Instruction>()
    open val instructionList: ArrayList<Instruction> get() = forms
    private lateinit var opcodeMatchMaps: List<MatchMap>

    open fun populate() {
        forms.clear()
        val loader = InstructionLoader()
        forms.addAll(loader.loadBasicInstructions())
        forms.addAll(loader.loadExtendedInstructions(this))
        forms.forEach { it.createExampleTokenList(this) }

        val matchesByMask = HashMap<Int, HashMap<Int, BasicInstruction>>()
        for (instruction in forms) {
            if (instruction is BasicInstruction) {
                val matches = matchesByMask.getOrPut(instruction.opcodeMask) { HashMap() }
                matches[instruction.opcodeMatch] = instruction
            }
        }
        // Decode the most specific mask first. Keep the original tie-breaking order.
        opcodeMatchMaps = matchesByMask.map { (mask, matches) -> MatchMap(mask, matches) }
            .sortedWith { left, right ->
                val specificity = right.mask.countOneBits() - left.mask.countOneBits()
                if (specificity != 0) specificity else left.mask - right.mask
            }
    }

    open fun findByBinaryCode(binaryInstr: Int): BasicInstruction? {
        for ((mask, matches) in opcodeMatchMaps) {
            val instruction = matches[binaryInstr and mask]
            if (instruction != null) return instruction
        }
        return null
    }

    open fun matchOperator(name: String?): ArrayList<Instruction>? =
        forms.filterTo(ArrayList()) { it.name.equals(name, ignoreCase = true) }.takeIf { it.isNotEmpty() }

    private data class MatchMap(val mask: Int, val instructions: Map<Int, BasicInstruction>)
}
