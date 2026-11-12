package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState.Companion.WORD_LENGTH_BYTES
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.util.Binary

open class InstrSwl : BasicInstruction(
    "swl \$t1,-100(\$t2)",
    "Store word left : Store high-order 1 to 4 bytes of \$t1 into memory, starting with"
        + " effective byte address and continuing through the low-order byte of its word",
    BasicInstructionFormat.I_FORMAT,
    "101010 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1]
            val source = context.registerFile.getValue(operands[0])
            for (index in 0..(address % WORD_LENGTH_BYTES)) {
                context.setMemoryByte(address - index, Binary.getByte(source, 3 - index))
            }
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
