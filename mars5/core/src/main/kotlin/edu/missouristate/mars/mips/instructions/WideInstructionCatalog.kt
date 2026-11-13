package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_STORE
import edu.missouristate.mars.simulator.Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION
import java.lang.Math.addExact
import java.lang.Math.subtractExact

/** Doubleword operations enabled by the educational MARS64 target. */
internal val wideInstructionFactories: List<() -> BasicInstruction> = listOf(
    { wideArithmetic("dadd", "Add doublewords and trap on signed overflow", "101100", ::addExact) },
    { wideArithmetic("daddu", "Add doublewords with wraparound", "101101", Long::plus) },
    { wideArithmetic("dsub", "Subtract doublewords and trap on signed overflow", "101110", ::subtractExact) },
    { wideArithmetic("dsubu", "Subtract doublewords with wraparound", "101111", Long::minus) },
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
                val first = memory.getWord(address).toUInt().toLong()
                val second = memory.getWord(address + Int.SIZE_BYTES).toUInt().toLong()
                val bits = if (memory.byteOrder) (second shl Int.SIZE_BITS) or first else (first shl Int.SIZE_BITS) or second
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

private fun wideArithmetic(
    name: String, description: String, function: String, operation: (Long, Long) -> Long,
) = BasicInstruction(
    "$name \$t1,\$t2,\$t3", description, R_FORMAT, "000000 sssss ttttt fffff 00000 $function",
) { statement, context ->
    val (destination, left, right) = statement.operands
    val registers = context.registerFile
    val result = try {
        operation(registers.getLongValue(left), registers.getLongValue(right))
    } catch (_: ArithmeticException) {
        throw ProcessingException(context, statement, "arithmetic overflow", ARITHMETIC_OVERFLOW_EXCEPTION)
    }
    context.updateLongRegister(destination, result)
}

private fun wideShift(
    name: String, description: String, function: String, base: Int, operation: (Long, Int) -> Long,
) = BasicInstruction(
    "$name \$t1,\$t2,10", description, R_FORMAT, "000000 00000 sssss fffff ttttt $function",
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
) = BasicInstruction(
    "$name \$t1,\$t2,\$t3", description, R_FORMAT, "000000 ttttt sssss fffff 00000 $function",
) { statement, context ->
    val (destination, source, shift) = statement.operands
    val registers = context.registerFile
    context.updateLongRegister(destination, operation(registers.getLongValue(source), registers.getValue(shift)))
}
