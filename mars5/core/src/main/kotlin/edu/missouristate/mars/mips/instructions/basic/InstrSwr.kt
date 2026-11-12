package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState.WORD_LENGTH_BYTES
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.util.Binary

open class InstrSwr : BasicInstruction(
    "swr \$t1,-100(\$t2)",
    "Store word right : Store low-order 1 to 4 bytes of \$t1 into memory, starting with"
        + " high-order byte of word containing effective byte address and continuing through"
        + " that byte address",
    BasicInstructionFormat.I_FORMAT,
    "101110 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1]
            val source = context.registerFile.getValue(operands[0])
            for (index in 0..(3 - (address % WORD_LENGTH_BYTES))) {
                context.setMemoryByte(address + index, Binary.getByte(source, index))
            }
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
