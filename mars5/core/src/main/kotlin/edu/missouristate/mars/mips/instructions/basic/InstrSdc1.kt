package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_STORE

open class InstrSdc1 : BasicInstruction(
    $$"sdc1 $f2,-100($t2)",
    $$"Store double word from Coprocessor 1 (FPU)) : Store 64 bit value in $f2 to effective"
        + " memory doubleword address",
    BasicInstructionFormat.I_FORMAT,
    "111101 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        if (!context.coprocessor1.hasWideRegisters && (operands[0] % 2 == 1)) {
            throw ProcessingException(context, statement, "first register must be even-numbered")
        }
        try {
            val address = context.effectiveAddress(operands[2], operands[1])
            if (!context.coprocessor1.hasWideRegisters && address % 8 != 0L) {
                throw AddressErrorException("address not aligned on doubleword boundary ", ADDRESS_EXCEPTION_STORE, address)
            }
            val fpu = context.coprocessor1
            if (fpu.hasWideRegisters) {
                val value = fpu.getLongFromRegisterPair(operands[0])
                context.storeDoubleword(address, value)
            } else {
                context.storeData(address, 4, fpu.getValue(operands[0]))
                context.storeData(address + 4, 4, fpu.getValue(operands[0] + 1))
            }
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
