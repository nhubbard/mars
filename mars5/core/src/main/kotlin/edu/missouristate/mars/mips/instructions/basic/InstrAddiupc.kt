package edu.missouristate.mars.mips.instructions.basic


class InstrAddiupc : PcRelativeWordInstruction(
    "addiupc \$t1,100000",
    "Add a byte displacement to the instruction address",
    "111011 fffff 00 sssssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        context.updateRegister(operands[0], statement.address + operands[1])
    },
)
