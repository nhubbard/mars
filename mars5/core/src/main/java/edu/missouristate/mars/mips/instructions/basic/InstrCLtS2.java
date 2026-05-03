package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrCLtS2 extends BasicInstruction {
    public InstrCLtS2() {
        super("c.lt.s 1,$f0,$f1",
              "Compare less than single precision : If $f0 is less than $f1, set Coprocessor 1 condition flag specified by immediate to true else set it to false",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 ttttt sssss fff 00 111100",
              statement -> {
              int[] operands = statement.getOperands();
              float op1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
              float op2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[2]));
              if (op1 < op2) Coprocessor1.setConditionFlag(operands[0]);
              else Coprocessor1.clearConditionFlag(operands[0]);
            });
    }
}
