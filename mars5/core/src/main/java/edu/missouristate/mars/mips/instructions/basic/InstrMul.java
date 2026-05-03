package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMul extends BasicInstruction {
    public InstrMul() {
        super("mul $t1,$t2,$t3",
              "Multiplication without overflow  : Set HI to high-order 32 bits, LO and $t1 to low-order 32 bits of the product of $t2 and $t3 (use mfhi to access HI, mflo to access LO)",
              BasicInstructionFormat.R_FORMAT,
              "011100 sssss ttttt fffff 00000 000010",
              statement -> {
              int[] operands = statement.getOperands();
              long product =
                  (long) RegisterFile.getValue(operands[1])
                      * (long) RegisterFile.getValue(operands[2]);
              RegisterFile.updateRegister(operands[0], (int) ((product << 32) >> 32));
              
              RegisterFile.updateRegister(33, (int) (product >> 32));
              RegisterFile.updateRegister(34, (int) ((product << 32) >> 32));
            });
    }
}
