package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrLl : BasicInstruction(
    "ll \$t1,-100(\$t2)",
    "Load linked : Paired with Store Conditional (sc) to perform atomic read-modify-write. "
        + " Treated as equivalent to Load Word (lw) because MARS does not simulate multiple"
        + " processors.",
    BasicInstructionFormat.I_FORMAT,
    "110000 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1]
            val value = context.memory().getWord(address)
            context.updateRegister(operands[0], value)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
