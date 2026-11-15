package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException

class InstrLwpc : PcRelativeWordInstruction(
    $$"lwpc $t1,100000",
    "Load a word at a byte displacement from the instruction address",
    "111011 fffff 01 sssssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        try {
            val address = statement.addressBits + operands[1]
            val value = context.loadPcRelativeWord(address)
            context.updateRegister(operands[0], value)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    },
)
