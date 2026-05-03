package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSra extends BasicInstruction {
    public InstrSra() {
        super("sra $t1,$t2,10",
              "Shift right arithmetic : Set $t1 to result of sign-extended shifting $t2 right by number of bits specified by immediate",
              BasicInstructionFormat.R_FORMAT,
              "000000 00000 sssss fffff ttttt 000011",
              statement -> {
              int[] operands = statement.getOperands();
              
              RegisterFile.updateRegister(
                  operands[0], RegisterFile.getValue(operands[1]) >> operands[2]);
            });
    }
}
