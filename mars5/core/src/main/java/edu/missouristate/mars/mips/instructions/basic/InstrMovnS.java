package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMovnS extends BasicInstruction {
    public InstrMovnS() {
        super("movn.s $f0,$f1,$t3",
              "Move floating point single precision : If $t3 is not zero, set single precision $f0 to single precision value in $f1",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 ttttt sssss fffff 010011",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[2]) != 0)
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
            });
    }
}
