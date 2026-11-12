package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSb : BasicInstruction(
    "sb \$t1,-100(\$t2)",
    "Store byte : Store the low-order 8 bits of \$t1 into the effective memory byte address",
    BasicInstructionFormat.I_FORMAT,
    "101000 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1].toShort().toInt()
            val value = context.registerFile.getValue(operands[0]) and 0xff
            context.setMemoryByte(address, value)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
