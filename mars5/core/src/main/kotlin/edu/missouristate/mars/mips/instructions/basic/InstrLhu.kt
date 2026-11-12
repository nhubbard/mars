package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrLhu : BasicInstruction(
    "lhu \$t1,-100(\$t2)",
    "Load halfword unsigned : Set \$t1 to zero-extended 16-bit value from effective memory"
        + " halfword address",
    BasicInstructionFormat.I_FORMAT,
    "100101 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1].toShort().toInt()
            val value = context.memory().getHalf(address) and 0xffff
            context.updateRegister(operands[0], value)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
