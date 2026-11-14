package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

internal fun msaRegisterInstructions(): List<BasicInstruction> = buildList {
    for ((format, suffix) in "bhwd".withIndex()) add(fillVector(format, suffix))
    add(BasicInstruction(
        "move.v \$w1,\$w2", "Copy a 128-bit vector register",
        R_FORMAT, "011110 00101 11110 sssss fffff 011001",
    ) { statement, context ->
        val (destination, source) = statement.operands
        context.updateVectorRegister(destination, context.coprocessor1.readVector(source))
    })
    add(vectorLogic("and", "00000", Long::and))
    add(vectorLogic("or", "00001", Long::or))
    add(vectorLogic("nor", "00010") { left, right -> (left or right).inv() })
    add(vectorLogic("xor", "00011", Long::xor))
}

private fun fillVector(format: Int, suffix: Char): BasicInstruction {
    val formatField = format.toString(2).padStart(5, '0')
    val elementBits = Byte.SIZE_BITS shl format
    val mask = (ULong.MAX_VALUE shr (Long.SIZE_BITS - elementBits)).toLong()
    return BasicInstruction(
        "fill.$suffix \$w1,\$t2", "Fill every vector element from a CPU register",
        R_FORMAT, "011110 11000 $formatField sssss fffff 011110",
    ) { statement, context ->
        val (destination, source) = statement.operands
        val element = context.registerFile.getLongValue(source) and mask
        var repeated = 0L
        for (shift in 0 until Long.SIZE_BITS step elementBits) {
            repeated = repeated or (element shl shift)
        }
        context.updateVectorRegister(destination, VectorRegister(repeated, repeated))
    }
}

private fun vectorLogic(mnemonic: String, opcode: String, apply: (Long, Long) -> Long) = BasicInstruction(
    "$mnemonic.v \$w1,\$w2,\$w3", "Apply $mnemonic to every bit of two vector registers",
    R_FORMAT, "011110 $opcode ttttt sssss fffff 011110",
) { statement, context ->
    val (destination, left, right) = statement.operands
    val a = context.coprocessor1.readVector(left)
    val b = context.coprocessor1.readVector(right)
    context.updateVectorRegister(destination, VectorRegister(apply(a.low, b.low), apply(a.high, b.high)))
}
