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
