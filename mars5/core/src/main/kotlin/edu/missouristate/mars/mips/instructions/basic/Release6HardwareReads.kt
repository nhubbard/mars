package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION

internal fun release6HardwareReads() = listOf(hardwareRead(hasSelector = false), hardwareRead(hasSelector = true))

private val readableRegisters = setOf(0, 1, 5)

private fun hardwareRead(hasSelector: Boolean) = object : BasicInstruction(
    if (hasSelector) $$"rdhwr $t1,$0,7" else $$"rdhwr $t1,$0",
    "Read CPU number, instruction synchronization step, or paired LL/SC support",
    R_FORMAT, "011111 00000 fffff sssss 00 ${if (hasSelector) "ttt" else "000"} 111011",
    { statement, context ->
        val (destination, register) = statement.operands
        val selector = if (hasSelector) statement.operands[2] else 0
        // One CPU, synchronous instruction fetch, and paired LL/SC are modeled.
        if (selector != 0 || register !in readableRegisters) {
            throw ProcessingException(context, statement, "Unavailable hardware register $register, selector $selector",
                RESERVED_INSTRUCTION_EXCEPTION)
        }
        context.updateRegister(destination, 0)
    },
) {
    override fun encodeOperands(values: IntArray): IntArray {
        require(!hasSelector || values[2] in 0..7) { "rdhwr selector must be in the range 0 through 7" }
        return values
    }
}
