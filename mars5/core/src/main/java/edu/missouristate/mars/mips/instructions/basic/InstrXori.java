package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrXori extends BasicInstruction {
    public InstrXori() {
        super("xori $t1,$t2,100",
              "Bitwise XOR immediate : Set $t1 to bitwise XOR of $t2 and zero-extended 16-bit immediate",
              BasicInstructionFormat.I_FORMAT,
              "001110 sssss fffff tttttttttttttttt",
              statement -> {
              int[] operands = statement.getOperands();
              
              RegisterFile.updateRegister(
                  operands[0], RegisterFile.getValue(operands[1]) ^ (operands[2] & 0x0000FFFF));
            });
    }
}
