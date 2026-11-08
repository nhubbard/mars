package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrSwr extends BasicInstruction {
  public InstrSwr() {
    super(
        "swr $t1,-100($t2)",
        "Store word right : Store low-order 1 to 4 bytes of $t1 into memory, starting with"
            + " high-order byte of word containing effective byte address and continuing through"
            + " that byte address",
        BasicInstructionFormat.I_FORMAT,
        "101110 ttttt fffff ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          try {
            int address = context.registerFile.getValue(operands[2]) + operands[1];
            int source = context.registerFile.getValue(operands[0]);
            for (int i = 0; i <= 3 - (address % MemoryState.WORD_LENGTH_BYTES); i++) {
              context.setMemoryByte(address + i, Binary.getByte(source, i));
            }
          } catch (AddressErrorException e) {
            throw new ProcessingException(context, statement, e);
          }
        });
  }
}
