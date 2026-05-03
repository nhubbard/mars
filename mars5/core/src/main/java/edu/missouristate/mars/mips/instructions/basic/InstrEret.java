package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrEret extends BasicInstruction {
    public InstrEret() {
        super("eret",
              "Exception return : Set Program Counter to Coprocessor 0 EPC register value, set Coprocessor Status register bit 1 (exception level) to zero",
              BasicInstructionFormat.R_FORMAT,
              "010000 1 0000000000000000000 011000",
              statement -> {
              
              Coprocessor0.updateRegister(
                  Coprocessor0.STATUS,
                  Binary.clearBit(
                      Coprocessor0.getValue(Coprocessor0.STATUS), Coprocessor0.EXCEPTION_LEVEL));
              RegisterFile.setProgramCounter(Coprocessor0.getValue(Coprocessor0.EPC));
            });
    }
}
