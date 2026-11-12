package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSc : BasicInstruction(
    "sc \$t1,-100(\$t2)",
    "Store conditional : Paired with Load Linked (ll) to perform atomic read-modify-write. "
        + " Stores \$t1 value into effective address, then sets \$t1 to 1 for success.  Always"
        + " succeeds because MARS does not simulate multiple processors.",
    BasicInstructionFormat.I_FORMAT,
    "111000 ttttt fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = context.registerFile.getValue(operands[2]) + operands[1]
            val value = context.registerFile.getValue(operands[0])
            context.setMemoryWord(address, value)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
        context.updateRegister(operands[0], 1)
    },
)
