package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.EPC
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.INTERRUPT_ENABLE
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.STATUS
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.VADDR
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION

internal fun release6SystemTransfers(): List<BasicInstruction> = listOf(
    systemTransfer(write = false, explicitSelector = false), systemTransfer(write = false, explicitSelector = true),
    systemTransfer(write = true, explicitSelector = false), systemTransfer(write = true, explicitSelector = true),
    exceptionReturn("eret", clearReservation = true), exceptionReturn("eretnc", clearReservation = false),
    interruptControl(enable = false, hasDestination = false), interruptControl(enable = false, hasDestination = true),
    interruptControl(enable = true, hasDestination = false), interruptControl(enable = true, hasDestination = true),
)

private fun interruptControl(enable: Boolean, hasDestination: Boolean): BasicInstruction {
    val mnemonic = if (enable) "ei" else "di"
    return BasicInstruction(
        if (hasDestination) "$mnemonic \$t1" else mnemonic,
        if (enable) "Enable interrupts and optionally return the previous Status" else "Disable interrupts and optionally return the previous Status",
        R_FORMAT, "010000 01011 ${if (hasDestination) "fffff" else "00000"} 01100 00000 ${if (enable) 1 else 0}00000",
    ) { statement, context ->
        val previous = context.coprocessor0.getValue(STATUS)
        if (hasDestination) context.updateRegister(statement.operands[0], previous)
        val status = if (enable) previous or INTERRUPT_ENABLE else previous and INTERRUPT_ENABLE.inv()
        context.updateCoprocessor0(STATUS, status)
    }
}

private fun exceptionReturn(mnemonic: String, clearReservation: Boolean): BasicInstruction = BasicInstruction(
    mnemonic, if (clearReservation) "Return through EPC and clear the reservation" else "Return through EPC and preserve the reservation",
    R_FORMAT, "010000 1 000000000000000000 ${if (clearReservation) 0 else 1} 011000",
) { statement, context -> context.release6ExceptionReturn(statement, clearReservation) }

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
