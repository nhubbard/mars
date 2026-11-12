package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSw : BasicInstruction(
    "sw \$t1,-100(\$t2)",
    "Store word : Store contents of \$t1 into effective memory word address",
    BasicInstructionFormat.I_FORMAT,
    "101011 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1]
            val value = context.registerFile.getValue(operands[0])
            context.setMemoryWord(address, value)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
