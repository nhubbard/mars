package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

/** Memory and instruction fetch are synchronous in the simulator, with no outstanding cache hazards. */
internal fun release6BarrierInstructions(): List<BasicInstruction> = listOf(
    BasicInstruction("sync", "Synchronize shared memory", R_FORMAT, "000000 00000 00000 00000 00000 001111") { _, _ -> },
    BasicInstruction("sync 31", "Synchronize shared memory", R_FORMAT, "000000 00000 00000 00000 fffff 001111") { _, _ -> },
    BasicInstruction("ehb", "Clear execution hazards", R_FORMAT, "000000 00000 00000 00000 00011 000000") { _, _ -> },
    object : BasicInstruction(
        "synci -100(\$t2)", "Synchronize instruction writes", I_FORMAT, "000001 sssss 11111 ffffffffffffffff",
        { statement, context ->
            val (offset, base) = statement.operands
            try {
                context.effectiveAddress(base, offset)
            } catch (failure: AddressErrorException) {
                throw ProcessingException(context, statement, failure)
            }
        },
    ) {
        override fun decodeOperands(values: IntArray) {
            values[0] = values[0].toShort().toInt()
        }
    },
    hazardJump("jalr.hb \$t1,\$t2", "sssss 00000 fffff", link = null),
    hazardJump("jalr.hb \$t1", "fffff 00000 11111", link = 31),
    hazardJump("jr.hb \$t1", "fffff 00000 00000", link = 0),
)

private fun hazardJump(example: String, registerFields: String, link: Int?) =
    object : BasicInstruction(
        example, "Jump after a delay slot and clear execution and instruction hazards", R_FORMAT,
        "000000 $registerFields 10000 001001",
        { statement, context ->
            val operands = statement.operands
            val source = operands[if (link == null) 1 else 0]
            context.release6DelayedJump(context.registerJumpTarget(statement, source), link ?: operands[0])
        },
    ) {
        override fun encodeOperands(values: IntArray): IntArray {
            if (link == null) {
                require(values[0] == 0 || values[0] != values[1]) {
                    "The link register and target register must be distinct"
                }
            } else if (link != 0) {
                require(values[0] != link) { "The link register and target register must be distinct" }
            }
            return values
        }
    }
