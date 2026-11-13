package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState.Companion.WORD_LENGTH_BYTES
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.util.Binary

open class InstrLwl : BasicInstruction(
    "lwl \$t1,-100(\$t2)",
    "Load word left : Load from 1 to 4 bytes left-justified into \$t1, starting with effective"
        + " memory byte address and continuing through the low-order byte of its word",
    BasicInstructionFormat.I_FORMAT,
    "100010 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1]
            // The byte merge reassigns result on each pass.
            @Suppress("CanBeVal")
            var result = context.registerFile.getValue(operands[0])
            for (index in 0..(address % WORD_LENGTH_BYTES)) {
                result = Binary.setByte(result, 3 - index, context.memory().getByte(address - index))
            }
            context.updateRegister(operands[0], result)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
