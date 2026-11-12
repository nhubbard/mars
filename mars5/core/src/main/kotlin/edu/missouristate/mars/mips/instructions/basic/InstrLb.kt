package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrLb : BasicInstruction(
    "lb \$t1,-100(\$t2)",
    "Load byte : Set \$t1 to sign-extended 8-bit value from effective memory byte address",
    BasicInstructionFormat.I_FORMAT,
    "100000 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1].toShort().toInt()
            val value = context.memory().getByte(address).toByte().toInt()
            context.updateRegister(operands[0], value)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
