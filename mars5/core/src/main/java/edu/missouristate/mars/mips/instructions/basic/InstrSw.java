package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSw extends BasicInstruction {
    public InstrSw() {
        super("sw $t1,-100($t2)",
              "Store word : Store contents of $t1 into effective memory word address",
              BasicInstructionFormat.I_FORMAT,
              "101011 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              try {
                Globals.memory.setWord(
                    RegisterFile.getValue(operands[2]) + operands[1],
                    RegisterFile.getValue(operands[0]));
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
