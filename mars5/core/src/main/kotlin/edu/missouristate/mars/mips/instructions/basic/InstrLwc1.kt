package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrLwc1 : BasicInstruction(
    "lwc1 \$f1,-100(\$t2)",
    "Load word into Coprocessor 1 (FPU) : Set \$f1 to 32-bit value from effective memory word"
        + " address",
    BasicInstructionFormat.I_FORMAT,
    "110001 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1]
            val value = context.memory().getWord(address)
            context.updateCoprocessor1(operands[0], value)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
