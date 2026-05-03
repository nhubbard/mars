package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrLwl extends BasicInstruction {
    public InstrLwl() {
        super("lwl $t1,-100($t2)",
              "Load word left : Load from 1 to 4 bytes left-justified into $t1, starting with effective memory byte address and continuing through the low-order byte of its word",
              BasicInstructionFormat.I_FORMAT,
              "100010 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              try {
                int address = RegisterFile.getValue(operands[2]) + operands[1];
                int result = RegisterFile.getValue(operands[0]);
                for (int i = 0; i <= address % Memory.WORD_LENGTH_BYTES; i++) {
                  result = Binary.setByte(result, 3 - i, Globals.memory.getByte(address - i));
                }
                RegisterFile.updateRegister(operands[0], result);
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
