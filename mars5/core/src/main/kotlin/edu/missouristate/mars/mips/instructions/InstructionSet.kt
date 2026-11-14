package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.instructions.basic.CompactComparison
import edu.missouristate.mars.mips.instructions.basic.InstrCompactCompareBranch
import edu.missouristate.mars.mips.instructions.basic.InstrRelease6Pref
import edu.missouristate.mars.mips.instructions.basic.release6RoundingInstructions
import edu.missouristate.mars.mips.instructions.basic.release6BarrierInstructions
import edu.missouristate.mars.mips.instructions.basic.release6FloatingCompareInstructions
import edu.missouristate.mars.mips.instructions.basic.release6FloatingConversions
import edu.missouristate.mars.mips.instructions.basic.release6HardwareReads
import edu.missouristate.mars.mips.instructions.basic.release6ScalarArithmetic
import edu.missouristate.mars.mips.instructions.basic.release6ScalarRoots
import edu.missouristate.mars.mips.instructions.basic.release6SystemTransfers
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
    internal var target = MarsTarget.MARS32
        private set
    private val forms = ArrayList<Instruction>()
    open val instructionList: ArrayList<Instruction> get() = forms
    private lateinit var opcodeMatchMaps: List<MatchMap>

    open fun populate() {
        populate(MarsTarget.MARS32)
    }

    internal fun populate(target: MarsTarget, includeLegacySourceForms: Boolean = false) {
        this.target = target
        forms.clear()
        val loader = InstructionLoader()
        val common = loader.loadBasicInstructions()
        forms.addAll(if (target == MarsTarget.MARS64) common.filterNot { it.name in replacedWordInstructions } else common)
        if (target == MarsTarget.MARS64) {
            forms.addAll(wideInstructionFactories.map { it() })
            forms.add(InstrRelease6Pref())
            forms.addAll(release6BarrierInstructions())
            forms.addAll(release6HardwareReads())
            forms.addAll(release6SystemTransfers())
            forms.addAll(msaRegisterInstructions())
            forms.addAll(msaIntegerInstructions())
            forms.addAll(msaBitInstructions())
            forms.addAll(msaWideningInstructions())
            forms.addAll(msaFixedPointInstructions())
            forms.addAll(msaFloatingCompareInstructions())
            forms.addAll(msaPermutationInstructions())
            forms.addAll(msaBranchInstructions())
            forms.addAll(release6FloatingCompareInstructions())
            forms.addAll(release6FloatingConversions())
            forms.addAll(release6ScalarArithmetic())
            forms.addAll(release6ScalarRoots())
            forms.addAll(release6RoundingInstructions())
            forms.addAll(CompactComparison.entries.filter { it.wideOnly }.map(::InstrCompactCompareBranch))
        }
        forms.addAll(loader.loadExtendedInstructions(this))
        forms.forEach { it.createExampleTokenList(this) }

        val matchesByMask = HashMap<Int, HashMap<Int, MutableList<BasicInstruction>>>()
        for (instruction in forms) {
            if (instruction is BasicInstruction) {
                val matches = matchesByMask.getOrPut(instruction.opcodeMask) { HashMap() }
                matches.getOrPut(instruction.opcodeMatch, ::ArrayList).add(instruction)
            }
        }
        // Decode the most specific mask first. Keep the original tie-breaking order.
        opcodeMatchMaps = matchesByMask.map { (mask, matches) -> MatchMap(mask, matches) }
            .sortedWith { left, right ->
                val specificity = right.mask.countOneBits() - left.mask.countOneBits()
                if (specificity != 0) specificity else left.mask - right.mask
            }
        if (includeLegacySourceForms) {
            val examples = forms.mapTo(HashSet()) { it.exampleFormat }
            forms.addAll(InstructionDefaults.get().instructionList.filter { examples.add(it.exampleFormat) })
        }
    }

    open fun findByBinaryCode(binaryInstr: Int): BasicInstruction? {
        for ((mask, matches) in opcodeMatchMaps) {
            val instructions = matches[binaryInstr and mask] ?: continue
            // Preserve the existing last-registration precedence for identical masks.
            for (index in instructions.lastIndex downTo 0) {
                val instruction = instructions[index]
                if (instruction.acceptsEncoding(binaryInstr)) return instruction
            }
        }
        return null
    }

    open fun matchOperator(name: String?): ArrayList<Instruction>? =
        forms.filterTo(ArrayList()) { it.name.equals(name, ignoreCase = true) }.takeIf { it.isNotEmpty() }

    private data class MatchMap(val mask: Int, val instructions: Map<Int, List<BasicInstruction>>)

    private companion object {
        val replacedWordInstructions = setOf(
            "addi", "mul", "div", "divu", "mult", "multu", "madd", "maddu", "msub", "msubu",
            "mfhi", "mflo", "mthi", "mtlo",
            "ll", "sc", "bc1f", "bc1t", "bltzal", "bgezal",
            "lwl", "lwr", "swl", "swr",
            "teqi", "tgei", "tgeiu", "tlti", "tltiu", "tnei",
            "jr", "jalr",
            "eret",
            "mfc0", "mtc0",
            "clz", "clo",
            "round.w.s", "round.w.d", "trunc.w.s", "trunc.w.d",
            "ceil.w.s", "ceil.w.d", "floor.w.s", "floor.w.d", "cvt.w.s", "cvt.w.d",
            "cvt.s.d", "cvt.s.w", "cvt.d.s", "cvt.d.w",
            "c.eq.s", "c.eq.d", "c.lt.s", "c.lt.d", "c.le.s", "c.le.d",
            "add.s", "add.d", "sub.s", "sub.d", "mul.s", "mul.d", "div.s", "div.d",
            "maddf.s", "maddf.d", "msubf.s", "msubf.d",
            "sqrt.s", "sqrt.d",
        )
    }
}
