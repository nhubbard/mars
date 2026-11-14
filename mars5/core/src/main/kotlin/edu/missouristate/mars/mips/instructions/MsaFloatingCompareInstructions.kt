package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.CAUSE_MASK
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.FLUSH_SUBNORMALS
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INVALID_OPERATION
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.MSA_NON_TRAPPING
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.mips.instructions.basic.floatingClass

internal fun msaFloatingCompareInstructions(): List<BasicInstruction> = buildList {
    for ((format, suffix) in "wd".withIndex()) {
        val bits = 32 shl format
        add(BasicInstruction(
            "fclass.$suffix \$w1,\$w2", "Classify each floating-point element",
            R_FORMAT, "011110 11001 0000$format sssss fffff 011110",
        ) { statement, context ->
            val (destination, source) = statement.operands
            val input = context.coprocessor1.readVector(source)
            context.updateVectorRegister(destination, mapLanes(bits) { half, shift ->
                val value = element(half(input), shift, bits)
                floatingClass(value, if (bits == 32) 23 else 52, if (bits == 32) 8 else 11).toLong()
            })
        })
        for (signaling in listOf(false, true)) {
            for ((opcode, name) in listOf("af", "un", "eq", "ueq", "lt", "ult", "le", "ule").withIndex()) {
                add(compare(name, opcode, format, suffix, signaling, complement = false))
            }
            for ((index, name) in listOf("or", "une", "ne").withIndex()) {
                add(compare(name, index + 1, format, suffix, signaling, complement = true))
            }
        }
    }
}

private fun compare(name: String, predicate: Int, format: Int, suffix: Char, signaling: Boolean, complement: Boolean): BasicInstruction {
    val field = ((if (signaling) 16 else 0) + predicate * 2 + format).toString(2).padStart(5, '0')
    val function = if (complement) "011100" else "011010"
    val bits = 32 shl format
    val exponent = if (format == 0) 0x7f800000L else 0x7ff0000000000000L
    val quiet = if (format == 0) 1L shl 22 else 1L shl 51
    val fraction = (quiet shl 1) - 1
    val mnemonic = "f${if (signaling) 's' else 'c'}$name.$suffix"
    return BasicInstruction(
        "$mnemonic \$w1,\$w2,\$w3", "Compare floating-point elements and write truth masks",
        R_FORMAT, "011110 $field ttttt sssss fffff $function",
    ) { statement, context ->
        val (destination, source, target) = statement.operands
        val fpu = context.coprocessor1
        val a = fpu.readVector(source)
        val b = fpu.readVector(target)
        val status = fpu.msaStatus
        val flush = status and FLUSH_SUBNORMALS != 0
        val encodeException = status and MSA_NON_TRAPPING != 0 && status and (INVALID_OPERATION shl 7) != 0
        var causes = 0
        fun number(raw: Long): Double {
            if (flush && raw and exponent == 0L) return 0.0
            return if (format == 0) Float.fromBits(raw.toInt()).toDouble() else Double.fromBits(raw)
        }
        fun isNan(raw: Long) = raw and exponent == exponent && raw and fraction != 0L
        val result = mapLanes(bits) lane@{ half, shift ->
            val left = element(half(a), shift, bits)
            val right = element(half(b), shift, bits)
            val leftNan = isNan(left)
            val rightNan = isNan(right)
            val unordered = leftNan || rightNan
            val invalid = (signaling && unordered) || (leftNan && left and quiet == 0L) || (rightNan && right and quiet == 0L)
            if (invalid && encodeException) return@lane exponent or INVALID_OPERATION.toLong()
            if (invalid) causes = INVALID_OPERATION
            val relation = if (unordered) 1 else {
                val x = number(left)
                val y = number(right)
                when {
                    x < y -> 4
                    x == y -> 2
                    else -> 0
                }
            }
            if ((relation and predicate != 0) != complement) -1L else 0L
        }
        val updated = (status and CAUSE_MASK.inv()) or (causes shl 12)
        val trapped = ((updated ushr 7) and causes) != 0
        context.writeMsaStatus(statement, if (trapped) updated else updated or (causes shl 2))
        context.updateVectorRegister(destination, result)
    }
}
