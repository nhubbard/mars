package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION

internal class InstrRelease6Pref :
    BasicInstruction(
        $$"pref 1,-100($t2)",
        "Prefetch hint. Supported hints have no effect in MARS64's synchronous memory model.",
        I_FORMAT,
        "011111 ttttt fffff sssssssss 0 110101",
        { statement, context ->
          val hint = statement.operands[0]
          if (hint >= 24) {
            throw ProcessingException(
                context,
                statement,
                "Reserved Release 6 prefetch hint $hint",
                RESERVED_INSTRUCTION_EXCEPTION,
            )
          }
        },
    ) {
  override fun encodeOperands(values: IntArray): IntArray {
    require(values[1] in -256..255) {
      "pref displacement must be in the range -256 through 255 bytes"
    }
    return values
  }

  override fun decodeOperands(values: IntArray) {
    values[1] = values[1].shl(23).shr(23)
  }
}
