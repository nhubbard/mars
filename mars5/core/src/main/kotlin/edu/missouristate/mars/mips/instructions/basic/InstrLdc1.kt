package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD

open class InstrLdc1 : BasicInstruction(
    $$"ldc1 $f2,-100($t2)",
    $$"Load double word Coprocessor 1 (FPU)) : Set $f2 to 64-bit value from effective memory"
        + " doubleword address",
    BasicInstructionFormat.I_FORMAT,
    "110101 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        if (!context.coprocessor1.hasWideRegisters && (operands[0] % 2 == 1)) {
            throw ProcessingException(context, statement, "first register must be even-numbered")
        }
        try {
            val address = context.effectiveAddress(operands[2], operands[1])
            if (!context.coprocessor1.hasWideRegisters && address % 8 != 0L) {
                throw AddressErrorException("address not aligned on doubleword boundary ", ADDRESS_EXCEPTION_LOAD, address)
            }
            val memory = context.memory()
            if (context.coprocessor1.hasWideRegisters) {
                val value = context.loadDoubleword(address)
                context.updateCoprocessor1Doubleword(operands[0], value)
            } else {
                context.updateCoprocessor1(operands[0], memory.readData(address, 4))
                context.updateCoprocessor1(operands[0] + 1, memory.readData(address + 4, 4))
            }
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
