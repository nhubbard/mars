package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState.WORD_LENGTH_BYTES
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.util.Binary

open class InstrLwr : BasicInstruction(
    "lwr \$t1,-100(\$t2)",
    "Load word right : Load from 1 to 4 bytes right-justified into \$t1, starting with effective"
        + " memory byte address and continuing through the high-order byte of its word",
    BasicInstructionFormat.I_FORMAT,
    "100110 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1]
            var result = context.registerFile.getValue(operands[0])
            for (index in 0..(3 - (address % WORD_LENGTH_BYTES))) {
                result = Binary.setByte(result, index, context.memory().getByte(address + index))
            }
            context.updateRegister(operands[0], result)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
