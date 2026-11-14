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
    for ((format, suffix) in "bhwd".withIndex()) {
        add(elementTransfer(suffix, format, "copy_s", "0010"))
        if (format < 3) add(elementTransfer(suffix, format, "copy_u", "0011"))
        add(elementTransfer(suffix, format, "insert", "0100"))
    }
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


private fun elementTransfer(suffix: Char, format: Int, mnemonic: String, operation: String): BasicInstruction {
    val insert = mnemonic == "insert"
    val signed = mnemonic == "copy_s"
    val bits = Byte.SIZE_BITS shl format
    val mask = (ULong.MAX_VALUE shr (Long.SIZE_BITS - bits)).toLong()
    val laneCount = 128 / bits
    val selector = "1".repeat(format) + "00" + (if (insert) "s" else "t").repeat(4 - format)
    val fields = if (insert) "ttttt fffff" else "sssss fffff"
    val example = if (insert) "\$w1[0],\$t2" else "\$t1,\$w2[0]"
    val laneOperand = if (insert) 1 else 2
    return object : BasicInstruction(
        "$mnemonic.$suffix $example", "Transfer one vector element between a vector and CPU register",
        R_FORMAT, "011110 $operation$selector $fields 011001",
        { statement, context ->
            val (destination, sourceOrLane, laneOrSource) = statement.operands
            val lane = statement.operands[laneOperand]
            val bitIndex = lane * bits
            val shift = bitIndex % Long.SIZE_BITS
            val high = bitIndex >= Long.SIZE_BITS
            val vector = context.coprocessor1.readVector(if (insert) destination else sourceOrLane)
            val half = if (high) vector.high else vector.low
            if (insert) {
                val element = context.registerFile.getLongValue(laneOrSource) and mask
                val updated = (half and (mask shl shift).inv()) or (element shl shift)
                context.updateVectorRegister(destination, if (high) vector.copy(high = updated) else vector.copy(low = updated))
            } else {
                val element = (half ushr shift) and mask
                val extension = Long.SIZE_BITS - bits
                val result = if (signed) (element shl extension) shr extension else element
                context.updateLongRegister(destination, result)
            }
        },
    ) {
        override fun encodeOperands(values: IntArray): IntArray {
            require(values[laneOperand] in 0 until laneCount) { "Vector lane must be between 0 and ${laneCount - 1}" }
            return values
        }
    }
}
