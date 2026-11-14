package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.EPC
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.VADDR
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION

internal fun release6SystemTransfers(): List<BasicInstruction> = listOf(
    systemTransfer(write = false, explicitSelector = false), systemTransfer(write = false, explicitSelector = true),
    systemTransfer(write = true, explicitSelector = false), systemTransfer(write = true, explicitSelector = true),
)

private fun systemTransfer(write: Boolean, explicitSelector: Boolean): BasicInstruction {
    val mnemonic = if (write) "dmtc0" else "dmfc0"
    val description = if (write) "Move a doubleword into an educational CP0 register" else "Read a doubleword from an educational CP0 register"
    return object : BasicInstruction(
        "$mnemonic \$t1,\$14${if (explicitSelector) ",7" else ""}", description,
        R_FORMAT, "010000 ${if (write) "00101" else "00001"} fffff sssss 00000000 ${if (explicitSelector) "ttt" else "000"}",
        { statement, context ->
            val (integer, system) = statement.operands
            val selector = if (explicitSelector) statement.operands[2] else 0
            if (selector != 0 || context.coprocessor0.getRegister(system) == null) {
                throw ProcessingException(context, statement, "Unavailable CP0 register $system, selector $selector",
                    RESERVED_INSTRUCTION_EXCEPTION)
            }
            val wide = system == EPC || system == VADDR
            if (write) {
                val bits = context.readIntegerOperand(integer)
                context.updateCoprocessor0Bits(system, if (wide) bits else bits.toInt().toLong())
            } else {
                val bits = if (wide) context.coprocessor0.readRegisterBits(system)
                else context.coprocessor0.getValue(system).toUInt().toLong()
                context.updateLongRegister(integer, bits)
            }
        },
    ) {
        override fun encodeOperands(values: IntArray): IntArray {
            require(!explicitSelector || values[2] in 0..7) { "$mnemonic selector must be in the range 0 through 7" }
            return values
        }
    }
}
