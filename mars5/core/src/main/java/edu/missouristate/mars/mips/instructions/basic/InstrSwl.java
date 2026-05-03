package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSwl extends BasicInstruction {
    public InstrSwl() {
        super("swl $t1,-100($t2)",
              "Store word left : Store high-order 1 to 4 bytes of $t1 into memory, starting with effective byte address and continuing through the low-order byte of its word",
              BasicInstructionFormat.I_FORMAT,
              "101010 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              try {
                int address = RegisterFile.getValue(operands[2]) + operands[1];
                int source = RegisterFile.getValue(operands[0]);
                for (int i = 0; i <= address % Memory.WORD_LENGTH_BYTES; i++) {
                  Globals.memory.setByte(address - i, Binary.getByte(source, 3 - i));
                }
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
