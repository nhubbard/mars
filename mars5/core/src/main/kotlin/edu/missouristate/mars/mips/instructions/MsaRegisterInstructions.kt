package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_STORE

internal fun msaRegisterInstructions(): List<BasicInstruction> = buildList {
    add(BasicInstruction(
        $$"cfcmsa $t1,$1", "Copy an MSA control register to a CPU register",
        R_FORMAT, "011110 00011 11110 sssss fffff 011001",
    ) { statement, context ->
        val (destination, control) = statement.operands
        context.updateRegister(destination, context.coprocessor1.readMsaControl(control))
    })
    add(BasicInstruction(
        $$"ctcmsa $1,$t1", "Copy a CPU register to the MSA control and status register",
        R_FORMAT, "011110 00001 11110 sssss fffff 011001",
    ) { statement, context ->
        val (control, source) = statement.operands
        if (control == 1) context.writeMsaStatus(statement, context.registerFile.getValue(source))
    })
    for ((format, suffix) in "bhwd".withIndex()) {
        add(fillVector(format, suffix))
        add(loadImmediate(format, suffix))
    }
    add(BasicInstruction(
        $$"move.v $w1,$w2", "Copy a 128-bit vector register",
        R_FORMAT, "011110 00101 11110 sssss fffff 011001",
    ) { statement, context ->
        val (destination, source) = statement.operands
        context.updateVectorRegister(destination, context.coprocessor1.readVector(source))
    })
    for ((format, suffix) in "bhwd".withIndex()) {
        add(elementTransfer(suffix, format, "copy_s", "0010"))
        if (format < 3) add(elementTransfer(suffix, format, "copy_u", "0011"))
        add(elementTransfer(suffix, format, "insert", "0100"))
        add(elementTransfer(suffix, format, "insve", "0101"))
        add(vectorMemory(suffix, format, store = false))
        add(vectorMemory(suffix, format, store = true))
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
        $$"fill.$$suffix $w1,$t2", "Fill every vector element from a CPU register",
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
    $$"$$mnemonic.v $w1,$w2,$w3", "Apply $mnemonic to every bit of two vector registers",
    R_FORMAT, "011110 $opcode ttttt sssss fffff 011110",
) { statement, context ->
    val (destination, left, right) = statement.operands
    val a = context.coprocessor1.readVector(left)
    val b = context.coprocessor1.readVector(right)
    context.updateVectorRegister(destination, VectorRegister(apply(a.low, b.low), apply(a.high, b.high)))
}


private fun elementTransfer(suffix: Char, format: Int, mnemonic: String, operation: String): BasicInstruction {
    val insert = mnemonic == "insert" || mnemonic == "insve"
    val vectorInsert = mnemonic == "insve"
    val signed = mnemonic == "copy_s"
    val bits = Byte.SIZE_BITS shl format
    val mask = (ULong.MAX_VALUE shr (Long.SIZE_BITS - bits)).toLong()
    val laneCount = 128 / bits
    val selector = "1".repeat(format) + "00" + (if (insert) "s" else "t").repeat(4 - format)
    val fields = if (insert) "ttttt fffff" else "sssss fffff"
    val example = when {
        vectorInsert -> $$"$w1[0],$w2[0]"
        insert -> $$"$w1[0],$t2"
        else -> $$"$t1,$w2[0]"
    }
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
                val source = if (vectorInsert) context.coprocessor1.readVector(laneOrSource).low
                else context.registerFile.getLongValue(laneOrSource)
                val element = source and mask
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
            require(!vectorInsert || values[3] == 0) { "INSVE source lane must be zero" }
            return values
        }

        override fun encodedOperandCount(sourceCount: Int) = if (vectorInsert) 3 else sourceCount

        override fun decodedOperandCount(encodedCount: Int) = if (vectorInsert) 4 else encodedCount
    }
}

private fun vectorMemory(suffix: Char, format: Int, store: Boolean): BasicInstruction {
    val mnemonic = if (store) "st" else "ld"
    val width = 1 shl format
    val elementMask = width - 1
    val operation = if (store) "1001" else "1000"
    val size = format.toString(2).padStart(2, '0')
    val exception = if (store) ADDRESS_EXCEPTION_STORE else ADDRESS_EXCEPTION_LOAD
    return object : BasicInstruction(
        $$"$$mnemonic.$$suffix $w1,-8($t2)", "Transfer 16 bytes of vector elements",
        I_FORMAT, "011110 ssssssssss ttttt fffff $operation$size",
        { statement, context ->
            val (register, offset, base) = statement.operands
            val memory = context.memory()
            try {
                val address = context.effectiveAddress(base, offset)
                memory.checkOrdinaryAccess(address, 16, exception)
                if (store) {
                    val (low, high) = context.coprocessor1.readVector(register)
                    for (index in 0 until 16) {
                        val vectorByte = if (memory.byteOrder) index else index xor elementMask
                        val half = if (vectorByte < 8) low else high
                        val value = (half ushr ((vectorByte and 7) * Byte.SIZE_BITS)).toInt() and 0xff
                        context.storeData(address + index, 1, value)
                    }
                } else {
                    var low = 0L
                    var high = 0L
                    for (index in 0 until 16) {
                        val vectorByte = if (memory.byteOrder) index else index xor elementMask
                        val value = (memory.readData(address + index, 1).toLong() and 0xff) shl ((vectorByte and 7) * Byte.SIZE_BITS)
                        if (vectorByte < 8) low = low or value else high = high or value
                    }
                    context.updateVectorRegister(register, VectorRegister(low, high))
                }
            } catch (failure: AddressErrorException) {
                throw ProcessingException(context, statement, failure)
            }
        },
    ) {
        override fun encodeOperands(values: IntArray): IntArray {
            val offset = values[1]
            require(offset % width == 0 && offset / width in -512..511) {
                "$mnemonic.$suffix requires a byte offset that fits a signed 10-bit field scaled by $width"
            }
            return values.copyOf().also { it[1] = offset / width }
        }

        override fun decodeOperands(values: IntArray) {
            values[1] = ((values[1] shl 22) shr 22) * width
        }
    }
}

private fun loadImmediate(format: Int, suffix: Char): BasicInstruction {
    val bits = Byte.SIZE_BITS shl format
    val field = format.toString(2).padStart(2, '0')
    return object : BasicInstruction(
        $$"ldi.$$suffix $w1,-512", "Broadcast a signed immediate into every vector element",
        R_FORMAT, "011110 110$field ssssssssss fffff 000111",
        { statement, context ->
            val (destination, value) = statement.operands
            context.updateVectorRegister(destination, mapLanes(bits) { _, _ -> value.toLong() })
        },
    ) {
        override fun encodeOperands(values: IntArray): IntArray {
            require(values[1] in -512..511) { "Vector immediate must be between -512 and 511" }
            return values
        }

        override fun decodeOperands(values: IntArray) {
            values[1] = (values[1] shl 22) shr 22
        }
    }
}
