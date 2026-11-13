package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_STORE

open class InstrSwc1 : BasicInstruction(
    "swc1 \$f1,-100(\$t2)",
    "Store word from Coprocesor 1 (FPU) : Store 32 bit value in \$f1 to effective memory word"
        + " address",
    BasicInstructionFormat.I_FORMAT,
    "111001 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.effectiveAddress(operands[2], operands[1], ADDRESS_EXCEPTION_STORE)
            val value = context.coprocessor1.getValue(operands[0])
            context.setMemoryWord(address, value)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
