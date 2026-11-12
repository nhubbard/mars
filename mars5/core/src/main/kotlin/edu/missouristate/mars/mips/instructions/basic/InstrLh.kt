package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrLh : BasicInstruction(
    "lh \$t1,-100(\$t2)",
    "Load halfword : Set \$t1 to sign-extended 16-bit value from effective memory halfword"
        + " address",
    BasicInstructionFormat.I_FORMAT,
    "100001 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1].toShort().toInt()
            val value = context.memory().getHalf(address).toShort().toInt()
            context.updateRegister(operands[0], value)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
