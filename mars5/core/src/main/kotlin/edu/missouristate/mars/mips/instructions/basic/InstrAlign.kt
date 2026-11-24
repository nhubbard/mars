package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrAlign :
    BasicInstruction(
        $$"align $t1,$t2,$t3,2",
        "Extract a word from two registers at a byte boundary",
        R_FORMAT,
        "011111 sssss ttttt fffff 010uu 100000",
        { statement, context ->
          val (destination, low, high, position) = statement.operands
          val registers = context.registerFile
          val left = registers.getValue(high)
          val right = registers.getValue(low).toUInt().toLong()
          val bits = position * Byte.SIZE_BITS
          context.updateRegister(
              destination,
              (left shl bits) or (right ushr (Int.SIZE_BITS - bits)).toInt(),
          )
        },
    ) {
  override fun encodeOperands(values: IntArray): IntArray {
    require(values[3] in 0..3) { "align byte position must be from 0 through 3" }
    return values
  }
}
