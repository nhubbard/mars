package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMsub extends BasicInstruction {
    public InstrMsub() {
        super("msub $t1,$t2",
              "Multiply subtract : Multiply $t1 by $t2 then decrement HI by high-order 32 bits of product, decrement LO by low-order 32 bits of product (use mfhi to access HI, mflo to access LO)",
              BasicInstructionFormat.R_FORMAT,
              "011100 fffff sssss 00000 00000 000100",
              statement -> {
              int[] operands = statement.getOperands();
              long product =
                  (long) RegisterFile.getValue(operands[0])
                      * (long) RegisterFile.getValue(operands[1]);
              
              long contentsHiLo =
                  Binary.twoIntsToLong(RegisterFile.getValue(33), RegisterFile.getValue(34));
              long diff = contentsHiLo - product;
              RegisterFile.updateRegister(33, Binary.highOrderLongToInt(diff));
              RegisterFile.updateRegister(34, Binary.lowOrderLongToInt(diff));
            });
    }
}
