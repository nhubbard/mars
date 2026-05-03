package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrBreak1 extends BasicInstruction {
    public InstrBreak1() {
        super("break 100",
              "Break execution with code : Terminate program execution with specified exception code",
              BasicInstructionFormat.R_FORMAT,
              "000000 ffffffffffffffffffff 001101",
              statement -> { 
              
              
              int[] operands = statement.getOperands();
              throw new ProcessingException(
                  statement,
                  "break instruction executed; code = " + operands[0] + ".",
                  Exceptions.BREAKPOINT_EXCEPTION);
            });
    }
}
