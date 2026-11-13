package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.util.Binary

/** Reads the high word first, preserving the order of the register access events. */
internal fun InstructionContext.readDoubleOperand(register: Int): Double {
    val high = coprocessor1.getValue(register + 1)
    val low = coprocessor1.getValue(register)
    return Double.fromBits(Binary.twoIntsToLong(high, low))
}

/** Arithmetic results use canonical NaNs. Register moves copy the original words. */
internal fun InstructionContext.writeDoubleResult(register: Int, value: Double) {
    val bits = value.toBits()
    updateCoprocessor1(register + 1, Binary.highOrderLongToInt(bits))
    updateCoprocessor1(register, Binary.lowOrderLongToInt(bits))
}

/** CLASS uses the IEEE 754 bit layout so sign, subnormals, and NaN payloads survive inspection. */
internal fun floatingClass(bits: Long, fractionBits: Int, exponentBits: Int): Int {
    val exponentMask = (1L shl exponentBits) - 1
    val fraction = bits and ((1L shl fractionBits) - 1)
    val exponent = (bits ushr fractionBits) and exponentMask
    if (exponent == exponentMask && fraction != 0L) {
        return if (fraction and (1L shl (fractionBits - 1)) == 0L) 1 else 2
    }
    val base = if (bits ushr (fractionBits + exponentBits) == 0L) 6 else 2
    val category = when {
        exponent == exponentMask -> 0
        exponent != 0L -> 1
        fraction != 0L -> 2
        else -> 3
    }
    return 1 shl (base + category)
}
