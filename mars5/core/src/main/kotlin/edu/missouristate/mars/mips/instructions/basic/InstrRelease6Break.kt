package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.simulator.Exceptions.BREAKPOINT_EXCEPTION

internal class InstrRelease6Break(private val hasLowCode: Boolean) :
    BasicInstruction(
        if (hasLowCode) "break 1023,1023" else "break 1023",
        "Raise a breakpoint exception with one or two unsigned 10-bit code fields",
        R_FORMAT,
        "000000 ffffffffff ${if (hasLowCode) "ssssssssss" else "0000000000"} 001101",
        { statement, context ->
          val (high, low) = statement.operands
          throw ProcessingException(
              context,
              statement,
              "break instruction executed; code = ${(high shl 10) or low}.",
              BREAKPOINT_EXCEPTION,
          )
        },
    ) {
  override fun encodeOperands(values: IntArray): IntArray {
    require(values[0] in 0..1023 && (!hasLowCode || values[1] in 0..1023)) {
      "break code operands must be unsigned 10-bit values"
    }
    return values
  }
}
