package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSub extends BasicInstruction {
    public InstrSub() {
        super("sub $t1,$t2,$t3",
              "Subtraction with overflow : set $t1 to ($t2 minus $t3)",
              BasicInstructionFormat.R_FORMAT,
              "000000 sssss ttttt fffff 00000 100010",
              statement -> {
              int[] operands = statement.getOperands();
              int sub1 = RegisterFile.getValue(operands[1]);
              int sub2 = RegisterFile.getValue(operands[2]);
              int dif = sub1 - sub2;
              
              if ((sub1 >= 0 && sub2 < 0 && dif < 0) || (sub1 < 0 && sub2 >= 0 && dif >= 0)) {
                throw new ProcessingException(
                    statement, "arithmetic overflow", Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION);
              }
              RegisterFile.updateRegister(operands[0], dif);
            });
    }
}
