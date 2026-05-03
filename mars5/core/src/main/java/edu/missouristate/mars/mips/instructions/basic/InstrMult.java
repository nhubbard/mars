package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMult extends BasicInstruction {
    public InstrMult() {
        super("mult $t1,$t2",
              "Multiplication : Set hi to high-order 32 bits, lo to low-order 32 bits of the product of $t1 and $t2 (use mfhi to access hi, mflo to access lo)",
              BasicInstructionFormat.R_FORMAT,
              "000000 fffff sssss 00000 00000 011000",
              statement -> {
              int[] operands = statement.getOperands();
              long product =
                  (long) RegisterFile.getValue(operands[0])
                      * (long) RegisterFile.getValue(operands[1]);
              
              RegisterFile.updateRegister(33, (int) (product >> 32));
              RegisterFile.updateRegister(34, (int) ((product << 32) >> 32));
            });
    }
}
