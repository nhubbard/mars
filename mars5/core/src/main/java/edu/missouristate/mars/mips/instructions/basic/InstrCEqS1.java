package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrCEqS1 extends BasicInstruction {
    public InstrCEqS1() {
        super("c.eq.s $f0,$f1",
              "Compare equal single precision : If $f0 is equal to $f1, set Coprocessor 1 condition flag 0 true else set it false",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 sssss fffff 00000 110010",
              statement -> {
              int[] operands = statement.getOperands();
              float op1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[0]));
              float op2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
              if (op1 == op2) Coprocessor1.setConditionFlag(0);
              else Coprocessor1.clearConditionFlag(0);
            });
    }
}
