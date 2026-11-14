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
