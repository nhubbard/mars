package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD

open class InstrLdc1 : BasicInstruction(
    "ldc1 \$f2,-100(\$t2)",
    "Load double word Coprocessor 1 (FPU)) : Set \$f2 to 64-bit value from effective memory"
        + " doubleword address",
    BasicInstructionFormat.I_FORMAT,
    "110101 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        if (operands[0] % 2 == 1) {
            throw ProcessingException(context, statement, "first register must be even-numbered")
        }
        if (!MemoryState.doublewordAligned(context.registerFile.getValue(operands[2]) + operands[1])) {
            val failure = AddressErrorException("address not aligned on doubleword boundary ", ADDRESS_EXCEPTION_LOAD, context.registerFile.getValue(operands[2]) + operands[1])
            throw ProcessingException(context, statement, failure)
        }
        try {
            context.updateCoprocessor1(operands[0], context.memory().getWord(context.registerFile.getValue(operands[2]) + operands[1]))
            context.updateCoprocessor1(operands[0] + 1, context.memory().getWord(context.registerFile.getValue(operands[2]) + operands[1] + 4))
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
