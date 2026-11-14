package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

internal fun msaWideningInstructions(): List<BasicInstruction> =
    (1..3).flatMap { format ->
        WideningOperation.entries.flatMap { operation ->
            listOf(wideningInstruction(operation, format, signed = true), wideningInstruction(operation, format, signed = false))
        }
    }

private enum class WideningOperation(val mnemonic: String, val opcode: Int, val function: String) {
    DOT("dotp", 0, "010011"),
    DOT_ADD("dpadd", 2, "010011"),
    DOT_SUBTRACT("dpsub", 4, "010011"),
    ADD("hadd", 4, "010101"),
    SUBTRACT("hsub", 6, "010101"),
}

private fun wideningInstruction(operation: WideningOperation, format: Int, signed: Boolean): BasicInstruction {
    val suffix = "bhwd"[format]
    val bits = Byte.SIZE_BITS shl format
    val narrow = bits / 2
    val opcode = operation.opcode + if (signed) 0 else 1
    val field = ((opcode shl 2) or format).toString(2).padStart(5, '0')
    val name = "${operation.mnemonic}_${if (signed) 's' else 'u'}.$suffix"
    return BasicInstruction(
        "$name \$w1,\$w2,\$w3", "Combine narrow elements into a wider integer result",
        R_FORMAT, "011110 $field ttttt sssss fffff ${operation.function}",
    ) { statement, context ->
        val (destination, source, target) = statement.operands
        val fpu = context.coprocessor1
        val left = fpu.readVector(source)
        val right = fpu.readVector(target)
        val previous = if (operation == WideningOperation.DOT_ADD || operation == WideningOperation.DOT_SUBTRACT) {
            fpu.readVector(destination)
        } else VectorRegister(0, 0)
        context.updateVectorRegister(destination, mapLanes(bits) { half, shift ->
            val odd = element(half(left), shift + narrow, narrow, signed)
            val even = element(half(right), shift, narrow, signed)
            when (operation) {
                WideningOperation.ADD -> odd + even
                WideningOperation.SUBTRACT -> odd - even
                else -> {
                    val dot = odd * element(half(right), shift + narrow, narrow, signed) +
                        even * element(half(left), shift, narrow, signed)
                    when (operation) {
                        WideningOperation.DOT_ADD -> element(half(previous), shift, bits) + dot
                        WideningOperation.DOT_SUBTRACT -> element(half(previous), shift, bits) - dot
                        else -> dot
                    }
                }
            }
        })
    }
}

internal fun msaFixedPointInstructions(): List<BasicInstruction> = buildList {
    for ((format, suffix) in "hw".withIndex()) {
        val bits = 16 shl format
        for ((operation, names) in listOf("mul_q" to "mulr_q", "madd_q" to "maddr_q", "msub_q" to "msubr_q").withIndex()) {
            add(fixedPointInstruction(names.first, suffix, bits, format, operation, rounded = false))
            add(fixedPointInstruction(names.second, suffix, bits, format, operation, rounded = true))
        }
    }
}

private fun fixedPointInstruction(
    name: String, suffix: Char, bits: Int, format: Int, operation: Int, rounded: Boolean,
): BasicInstruction {
    val field = ((if (rounded) 24 else 8) + operation * 2 + format).toString(2).padStart(5, '0')
    val maximum = (1L shl (bits - 1)) - 1
    val minimum = -maximum - 1
    val rounding = if (rounded) 1L shl (bits - 2) else 0L
    return BasicInstruction(
        "$name.$suffix \$w1,\$w2,\$w3", "Multiply signed fixed-point elements with saturation",
        R_FORMAT, "011110 $field ttttt sssss fffff 011100",
    ) { statement, context ->
        val (destination, source, target) = statement.operands
        val fpu = context.coprocessor1
        val left = fpu.readVector(source)
        val right = fpu.readVector(target)
        val previous = if (operation == 0) VectorRegister(0, 0) else fpu.readVector(destination)
        context.updateVectorRegister(destination, mapLanes(bits) { half, shift ->
            val product = element(half(left), shift, bits, signed = true) * element(half(right), shift, bits, signed = true)
            val accumulator = element(half(previous), shift, bits, signed = true) shl (bits - 1)
            val sum = accumulator + if (operation == 2) -product else product
            ((sum + rounding) shr (bits - 1)).coerceIn(minimum, maximum)
        })
    }
}
