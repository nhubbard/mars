package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrAbsS extends BasicInstruction {
    public InstrAbsS() {
        super("abs.s $f0,$f1",
              "Floating point absolute value single precision : Set $f0 to absolute value of $f1, single precision",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 00000 sssss fffff 000101",
              statement -> {
              int[] operands = statement.getOperands();
              
              Coprocessor1.updateRegister(
                  operands[0], Coprocessor1.getValue(operands[1]) & Integer.MAX_VALUE);
            });
    }
}
