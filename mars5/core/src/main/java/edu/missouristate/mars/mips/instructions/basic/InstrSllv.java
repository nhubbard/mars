package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSllv extends BasicInstruction {
    public InstrSllv() {
        super("sllv $t1,$t2,$t3",
              "Shift left logical variable : Set $t1 to result of shifting $t2 left by number of bits specified by value in low-order 5 bits of $t3",
              BasicInstructionFormat.R_FORMAT,
              "000000 ttttt sssss fffff 00000 000100",
              statement -> {
              int[] operands = statement.getOperands();
              
              RegisterFile.updateRegister(
                  operands[0],
                  RegisterFile.getValue(operands[1])
                      << (RegisterFile.getValue(operands[2]) & 0x0000001F));
            });
    }
}
