package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrBreak2 extends BasicInstruction {
    public InstrBreak2() {
        super("break",
              "Break execution : Terminate program execution with exception",
              BasicInstructionFormat.R_FORMAT,
              "000000 00000 00000 00000 00000 001101",
              statement -> { 
              
              
              throw new ProcessingException(
                  statement,
                  "break instruction executed; no code given.",
                  Exceptions.BREAKPOINT_EXCEPTION);
            });
    }
}
