package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.FLUSH_SUBNORMALS
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INVALID_OPERATION
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION

internal fun release6FloatingCompareInstructions(): List<BasicInstruction> = buildList {
    add(controlMove(toFpu = false))
    add(controlMove(toFpu = true))
    for ((predicate, names) in predicates.withIndex()) {
        for (name in names.asReversed()) {
            add(compareMask(name, predicate, double = false))
            add(compareMask(name, predicate, double = true))
        }
    }
}

// Indexed by condn. Preserve documentation spellings and aliases accepted by LLVM.
private val predicates = listOf(
    listOf("af"), listOf("un"), listOf("eq"), listOf("ueq"),
    listOf("lt", "olt"), listOf("ult"), listOf("le", "ole"), listOf("ule"),
    listOf("saf"), listOf("sun", "sub"), listOf("seq"), listOf("sueq"),
    listOf("slt"), listOf("sult"), listOf("sle"), listOf("sule"),
    emptyList(), listOf("or"), listOf("une"), listOf("ne"),
    emptyList(), emptyList(), emptyList(), emptyList(),
    emptyList(), listOf("sor"), listOf("sune"), listOf("sne"),
)

private fun controlMove(toFpu: Boolean) = BasicInstruction(
    if (toFpu) $$"ctc1 $t1,$31" else $$"cfc1 $t1,$31",
    if (toFpu) "Write an FPU control register from a CPU register" else "Read an FPU control register into a CPU register",
    R_FORMAT,
    "010001 ${if (toFpu) "00110" else "00010"} fffff sssss 00000000000",
) { statement, context ->
    val (cpuRegister, controlRegister) = statement.operands
    if (toFpu) {
        context.writeFloatingControl(statement, controlRegister, context.registerFile.getValue(cpuRegister))
    } else {
        val value = context.coprocessor1.readControl(controlRegister)
            ?: throw ProcessingException(context, statement, "Reserved COP1 control register $controlRegister", RESERVED_INSTRUCTION_EXCEPTION)
        context.updateRegister(cpuRegister, value)
    }
}

private fun compareMask(name: String, predicate: Int, double: Boolean): BasicInstruction {
    val exponentMask = if (double) 0x7ff0_0000_0000_0000L else 0x7f80_0000L
    val fractionMask = if (double) 0x000f_ffff_ffff_ffffL else 0x007f_ffffL
    val quietBit = (fractionMask + 1) ushr 1
    val field = predicate.toString(2).padStart(5, '0')
    return BasicInstruction(
        $$"cmp.$$name.$${if (double) "d" else "s"} $f2,$f4,$f6",
        "Compare floating-point operands and write a full-width truth mask",
        R_FORMAT, "010001 ${if (double) "10101" else "10100"} ttttt sssss fffff 0$field",
    ) { statement, context ->
        val (destination, left, right) = statement.operands
        val fpu = context.coprocessor1
        fun read(register: Int): Long =
            if (double) fpu.getLongFromRegisterPair(register) else fpu.getValue(register).toUInt().toLong()

        val leftBits = read(left)
        val rightBits = read(right)
        fun isNaN(bits: Long) = bits and exponentMask == exponentMask && bits and fractionMask != 0L
        val leftNan = isNaN(leftBits)
        val rightNan = isNaN(rightBits)
        val unordered = leftNan || rightNan
        val invalid = (unordered && predicate and 8 != 0) ||
            (leftNan && leftBits and quietBit == 0L) || (rightNan && rightBits and quietBit == 0L)
        context.recordFloatingExceptions(statement, if (invalid) INVALID_OPERATION else 0)

        val relation = if (unordered) 1 else {
            val flush = fpu.floatingStatus and FLUSH_SUBNORMALS != 0
            fun number(bits: Long): Double {
                if (flush && bits and exponentMask == 0L) return 0.0
                return if (double) Double.fromBits(bits) else Float.fromBits(bits.toInt()).toDouble()
            }
            val a = number(leftBits)
            val b = number(rightBits)
            when {
                a < b -> 4
                a == b -> 2
                else -> 0
            }
        }
        val matches = (predicate and relation != 0) xor (predicate and 16 != 0)
        val mask = if (matches) -1 else 0
        if (double) context.updateCoprocessor1Doubleword(destination, mask.toLong())
        else context.updateCoprocessor1(destination, mask)
    }
}
