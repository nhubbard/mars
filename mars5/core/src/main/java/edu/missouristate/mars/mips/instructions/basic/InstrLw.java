package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrLw extends BasicInstruction {
    public InstrLw() {
        super("lw $t1,-100($t2)",
              "Load word : Set $t1 to contents of effective memory word address",
              BasicInstructionFormat.I_FORMAT,
              "100011 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              try {
                RegisterFile.updateRegister(
                    operands[0],
                    Globals.memory.getWord(RegisterFile.getValue(operands[2]) + operands[1]));
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
