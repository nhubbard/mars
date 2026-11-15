package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrLsa : BasicInstruction(
    $$"lsa $t1,$t2,$t3,2",
    "Shift the first source register left and add the second source register",
    R_FORMAT,
    "000000 sssss ttttt fffff 000uu 000101",
    { statement, context ->
        val (destination, index, base, shift) = statement.operands
        val registers = context.registerFile
        context.updateRegister(destination, (registers.getValue(index) shl shift) + registers.getValue(base))
    },
) {
    override fun encodeOperands(values: IntArray): IntArray {
        require(values[3] in 1..4) { "lsa shift must be from 1 through 4" }
        return values.copyOf().also { it[3]-- }
    }

    override fun decodeOperands(values: IntArray) {
        values[3]++
    }
}
