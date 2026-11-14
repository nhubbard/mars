package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.mips.instructions.basic.DoublewordBitField
import edu.missouristate.mars.mips.instructions.basic.PcRelativeWordInstruction
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_STORE
import edu.missouristate.mars.simulator.Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION
import edu.missouristate.mars.simulator.Exceptions.TRAP_EXCEPTION
import java.lang.Long.divideUnsigned
import java.lang.Long.remainderUnsigned
import java.lang.Long.reverse
import java.lang.Long.reverseBytes
import java.lang.Math.addExact
import java.lang.Math.multiplyHigh
import java.lang.Math.subtractExact
import java.lang.Math.unsignedMultiplyHigh

/** Doubleword operations enabled by the educational MARS64 target. */
internal val wideInstructionFactories: List<() -> BasicInstruction> = listOf(
    { wideArithmetic("dadd", "Add doublewords and trap on signed overflow", "101100", ::addExact) },
    { wideArithmetic("daddu", "Add doublewords with wraparound", "101101", Long::plus) },
    { wideArithmetic("dsub", "Subtract doublewords and trap on signed overflow", "101110", ::subtractExact) },
    { wideArithmetic("dsubu", "Subtract doublewords with wraparound", "101111", Long::minus) },
    { wideArithmetic("dmul", "Multiply signed doublewords and keep the low half", "011100", Long::times, "00010") },
    { wideArithmetic("dmuh", "Multiply signed doublewords and keep the high half", "011100", ::multiplyHigh, "00011") },
    { wideArithmetic("dmulu", "Multiply unsigned doublewords and keep the low half", "011101", Long::times, "00010") },
    { wideArithmetic("dmuhu", "Multiply unsigned doublewords and keep the high half", "011101", ::unsignedMultiplyHigh, "00011") },
    { wideArithmetic("ddiv", "Divide signed doublewords", "011110", Long::div, "00010", TRAP_EXCEPTION) },
    { wideArithmetic("dmod", "Find the remainder of signed doubleword division", "011110", Long::rem, "00011", TRAP_EXCEPTION) },
    { wideArithmetic("ddivu", "Divide unsigned doublewords", "011111", ::divideUnsigned, "00010", TRAP_EXCEPTION) },
    { wideArithmetic("dmodu", "Find the remainder of unsigned doubleword division", "011111", ::remainderUnsigned, "00011", TRAP_EXCEPTION) },
    {
        BasicInstruction(
            "daddiu \$t1,\$t2,-100", "Add a signed immediate to a doubleword with wraparound",
            I_FORMAT, "011001 sssss fffff tttttttttttttttt",
        ) { statement, context ->
            val (destination, source, immediate) = statement.operands
            context.updateLongRegister(destination, context.registerFile.getLongValue(source) + immediate.toShort())
        }
    },
    { wideShift("dsll", "Shift a doubleword left", "111000", 0, Long::shl) },
    { wideShift("dsll32", "Shift a doubleword left by 32 plus the immediate", "111100", 32, Long::shl) },
    { wideShift("dsrl", "Shift a doubleword right logically", "111010", 0, Long::ushr) },
    { wideShift("dsrl32", "Shift a doubleword right logically by 32 plus the immediate", "111110", 32, Long::ushr) },
    { wideShift("dsra", "Shift a doubleword right arithmetically", "111011", 0, Long::shr) },
    { wideShift("dsra32", "Shift a doubleword right arithmetically by 32 plus the immediate", "111111", 32, Long::shr) },
    { wideVariableShift("dsllv", "Shift a doubleword left by a register count", "010100", Long::shl) },
    { wideVariableShift("dsrlv", "Shift a doubleword right logically by a register count", "010110", Long::ushr) },
    { wideVariableShift("dsrav", "Shift a doubleword right arithmetically by a register count", "010111", Long::shr) },
    { wideShift("drotr", "Rotate a doubleword right", "111010", 0, Long::rotateRight, "00001") },
    { wideShift("drotr32", "Rotate a doubleword right by 32 plus the immediate", "111110", 32, Long::rotateRight, "00001") },
    { wideVariableShift("drotrv", "Rotate a doubleword right by a register count", "010110", Long::rotateRight, "00001") },
    { wideCount("dclz", "Count leading zero bits in a doubleword", "010010", Long::countLeadingZeroBits) },
    { wideCount("dclo", "Count leading one bits in a doubleword", "010011") { it.inv().countLeadingZeroBits() } },
    { DoublewordBitField(insert = false) },
    { DoublewordBitField(insert = false, variant = "m") },
    { DoublewordBitField(insert = false, variant = "u") },
    { DoublewordBitField(insert = true) },
    { DoublewordBitField(insert = true, variant = "m") },
    { DoublewordBitField(insert = true, variant = "u") },
    { pcRelativeWideLoad(doubleword = false) },
    { pcRelativeWideLoad(doubleword = true) },
    { wideUpperImmediate("dahi", "Add a signed immediate in bits 47 through 32", "00110", 32) },
    { wideUpperImmediate("dati", "Add a signed immediate in bits 63 through 48", "11110", 48) },
    {
        object : BasicInstruction(
            "daui \$t1,\$t2,65535", "Add a signed immediate shifted left by 16 to a doubleword",
            I_FORMAT, "011101 sssss fffff tttttttttttttttt",
            { statement, context ->
                val (destination, source, immediate) = statement.operands
                context.updateLongRegister(destination, context.registerFile.getLongValue(source) + (immediate.toShort().toLong() shl 16))
            },
        ) {
            override fun encodeOperands(values: IntArray): IntArray {
                require(values[1] != 0) { "daui source register must not be zero" }
                return values
            }

            override fun acceptsEncoding(word: Int): Boolean = word and 0x03e0_0000 != 0

        }
    },
    { widePermutation("dbitswap", "Reverse the bits within each byte of a doubleword", "00000") { reverse(reverseBytes(it)) } },
    {
        widePermutation("dsbh", "Swap the bytes within each halfword of a doubleword", "00010") { value ->
            val lowBytes = 0x00ff_00ff_00ff_00ffL
            ((value and lowBytes) shl 8) or ((value ushr 8) and lowBytes)
        }
    },
    {
        widePermutation("dshd", "Reverse the order of halfwords within a doubleword", "00101") { value ->
            val lowHalfwords = 0x0000_ffff_0000_ffffL
            (((value and lowHalfwords) shl 16) or ((value ushr 16) and lowHalfwords)).rotateLeft(32)
        }
    },
    {
        object : BasicInstruction(
            "dlsa \$t1,\$t2,\$t3,2", "Shift the first doubleword left and add the second doubleword",
            R_FORMAT, "000000 sssss ttttt fffff 000uu 010101",
            { statement, context ->
                val (destination, index, base, shift) = statement.operands
                val registers = context.registerFile
                context.updateLongRegister(destination, (registers.getLongValue(index) shl shift) + registers.getLongValue(base))
            },
        ) {
            override fun encodeOperands(values: IntArray): IntArray {
                require(values[3] in 1..4) { "dlsa shift must be from 1 through 4" }
                return values.copyOf().also { it[3]-- }
            }

            override fun decodeOperands(values: IntArray) {
                values[3]++
            }
        }
    },
    {
        object : BasicInstruction(
            "dalign \$t1,\$t2,\$t3,2", "Extract a doubleword from two registers at a byte boundary",
            R_FORMAT, "011111 sssss ttttt fffff 01uuu 100100",
            { statement, context ->
                val (destination, low, high, position) = statement.operands
                val registers = context.registerFile
                val upper = registers.getLongValue(high)
                val bits = position * Byte.SIZE_BITS
                val result = if (bits == 0) upper else (upper shl bits) or (registers.getLongValue(low) ushr (Long.SIZE_BITS - bits))
                context.updateLongRegister(destination, result)
            },
        ) {
            override fun encodeOperands(values: IntArray): IntArray {
                require(values[3] in 0..7) { "dalign byte position must be from 0 through 7" }
                return values
            }
        }
    },
    {
        BasicInstruction(
            "lwu \$t1,-100(\$t2)", "Load a word and zero-extend it to a doubleword",
            I_FORMAT, "100111 ttttt fffff ssssssssssssssss",
        ) { statement, context ->
            val (destination, offset, base) = statement.operands
            try {
                val address = context.effectiveAddress(base, offset, ADDRESS_EXCEPTION_LOAD)
                val bits = context.memory().getWord(address).toUInt().toLong()
                context.updateLongRegister(destination, bits)
            } catch (failure: AddressErrorException) {
                throw ProcessingException(context, statement, failure)
            }
        }
    },
    {
        BasicInstruction(
            "ld \$t1,-100(\$t2)", "Load an aligned doubleword",
            I_FORMAT, "110111 ttttt fffff ssssssssssssssss",
        ) { statement, context ->
            val (destination, offset, base) = statement.operands
            try {
                val address = context.effectiveAddress(base, offset, ADDRESS_EXCEPTION_LOAD)
                requireDoublewordAlignment(address, ADDRESS_EXCEPTION_LOAD)
                val memory = context.memory()
                val bits = readDoubleword(memory, address, memory::getWord)
                context.updateLongRegister(destination, bits)
            } catch (failure: AddressErrorException) {
                throw ProcessingException(context, statement, failure)
            }
        }
    },
    {
        BasicInstruction(
            "sd \$t1,-100(\$t2)", "Store an aligned doubleword",
            I_FORMAT, "111111 ttttt fffff ssssssssssssssss",
        ) { statement, context ->
            val (source, offset, base) = statement.operands
            try {
                val address = context.effectiveAddress(base, offset, ADDRESS_EXCEPTION_STORE)
                requireDoublewordAlignment(address, ADDRESS_EXCEPTION_STORE)
                val value = context.registerFile.getLongValue(source)
                val low = value.toInt()
                val high = (value ushr Int.SIZE_BITS).toInt()
                if (context.memory().byteOrder) {
                    context.setMemoryWord(address, low)
                    context.setMemoryWord(address + Int.SIZE_BYTES, high)
                } else {
                    context.setMemoryWord(address, high)
                    context.setMemoryWord(address + Int.SIZE_BYTES, low)
                }
            } catch (failure: AddressErrorException) {
                throw ProcessingException(context, statement, failure)
            }
        }
    },
)

private fun pcRelativeWideLoad(doubleword: Boolean): PcRelativeWordInstruction =
    object : PcRelativeWordInstruction(
        "${if (doubleword) "ldpc" else "lwupc"} \$t1,100000",
        if (doubleword) "Load a doubleword relative to the aligned instruction address"
        else "Load a zero-extended word relative to the instruction address",
        if (doubleword) "111011 fffff 110 ssssssssssssssssss"
        else "111011 fffff 10 sssssssssssssssssss",
        { statement, context ->
            val (destination, displacement) = statement.operands
            val base = if (doubleword) statement.address and -8 else statement.address
            val address = base + displacement
            val memory = context.memory()
            fun readWord(location: Int): Int =
                if (memory.inTextSegment(location) || memory.inKernelTextSegment(location)) {
                    memory.getStatement(location)?.binaryStatement ?: 0
                } else {
                    memory.getWord(location)
                }
            try {
                val value = if (doubleword) readDoubleword(memory, address, ::readWord)
                else readWord(address).toUInt().toLong()
                context.updateLongRegister(destination, value)
            } catch (failure: AddressErrorException) {
                throw ProcessingException(context, statement, failure)
            }
        },
        displacementShift = if (doubleword) 3 else 2,
    ) {}

private inline fun readDoubleword(memory: MemoryState, address: Int, readWord: (Int) -> Int): Long {
    val first = readWord(address).toUInt().toLong()
    val second = readWord(address + Int.SIZE_BYTES).toUInt().toLong()
    return if (memory.byteOrder) (second shl Int.SIZE_BITS) or first else (first shl Int.SIZE_BITS) or second
}

private fun wideUpperImmediate(name: String, description: String, selector: String, shift: Int) =
    object : BasicInstruction(
        "$name \$t1,\$t1,65535", description, I_FORMAT, "000001 fffff $selector ssssssssssssssss",
        { statement, context ->
            val (destination, _, immediate) = statement.operands
            val value = context.registerFile.getLongValue(destination)
            context.updateLongRegister(destination, value + (immediate.toShort().toLong() shl shift))
        },
    ) {
        override fun encodeOperands(values: IntArray): IntArray {
            require(values[0] == values[1]) { "$name source and destination registers must match" }
            return intArrayOf(values[0], values[2])
        }

        override fun encodedOperandCount(sourceCount: Int): Int = 2

        override fun decodeOperands(values: IntArray) {
            values[2] = values[1]
            values[1] = values[0]
        }

        override fun decodedOperandCount(encodedCount: Int): Int = 3
    }

private fun widePermutation(name: String, description: String, selector: String, operation: (Long) -> Long) =
    BasicInstruction(
        "$name \$t1,\$t2", description, R_FORMAT, "011111 00000 sssss fffff $selector 100100",
    ) { statement, context ->
        val (destination, source) = statement.operands
        context.updateLongRegister(destination, operation(context.registerFile.getLongValue(source)))
    }

private fun wideArithmetic(
    name: String, description: String, function: String, operation: (Long, Long) -> Long,
    modifier: String = "00000",
    fault: Int = ARITHMETIC_OVERFLOW_EXCEPTION,
) = BasicInstruction(
    "$name \$t1,\$t2,\$t3", description, R_FORMAT, "000000 sssss ttttt fffff $modifier $function",
) { statement, context ->
    val (destination, left, right) = statement.operands
    val registers = context.registerFile
    val result = try {
        operation(registers.getLongValue(left), registers.getLongValue(right))
    } catch (_: ArithmeticException) {
        val message = if (fault == TRAP_EXCEPTION) "division by zero" else "arithmetic overflow"
        throw ProcessingException(context, statement, message, fault)
    }
    context.updateLongRegister(destination, result)
}

private fun wideShift(
    name: String, description: String, function: String, base: Int, operation: (Long, Int) -> Long,
    modifier: String = "00000",
) = BasicInstruction(
    "$name \$t1,\$t2,10", description, R_FORMAT, "000000 $modifier sssss fffff ttttt $function",
) { statement, context ->
    val (destination, source, shift) = statement.operands
    context.updateLongRegister(destination, operation(context.registerFile.getLongValue(source), base + shift))
}

private fun requireDoublewordAlignment(address: Int, exception: Int) {
    if (address % Long.SIZE_BYTES != 0) {
        throw AddressErrorException("address not aligned on doubleword boundary ", exception, address)
    }
}

private fun wideVariableShift(
    name: String, description: String, function: String, operation: (Long, Int) -> Long,
    modifier: String = "00000",
) = BasicInstruction(
    "$name \$t1,\$t2,\$t3", description, R_FORMAT, "000000 ttttt sssss fffff $modifier $function",
) { statement, context ->
    val (destination, source, shift) = statement.operands
    val registers = context.registerFile
    context.updateLongRegister(destination, operation(registers.getLongValue(source), registers.getValue(shift)))
}

private fun wideCount(
    name: String, description: String, function: String, operation: (Long) -> Int,
) = BasicInstruction(
    "$name \$t1,\$t2", description, R_FORMAT, "000000 sssss 00000 fffff 00001 $function",
) { statement, context ->
    val (destination, source) = statement.operands
    context.updateRegister(destination, operation(context.registerFile.getLongValue(source)))
}
