package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrDivu extends BasicInstruction {
    public InstrDivu() {
        super("divu $t1,$t2",
              "Division unsigned without overflow : Divide unsigned $t1 by $t2 then set LO to quotient and HI to remainder (use mfhi to access HI, mflo to access LO)",
              BasicInstructionFormat.R_FORMAT,
              "000000 fffff sssss 00000 00000 011011",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[1]) == 0) {
                
                return;
              }
              long oper1 = ((long) RegisterFile.getValue(operands[0])) << 32 >>> 32;
              long oper2 = ((long) RegisterFile.getValue(operands[1])) << 32 >>> 32;
              
              RegisterFile.updateRegister(33, (int) (((oper1 % oper2) << 32) >> 32));
              RegisterFile.updateRegister(34, (int) (((oper1 / oper2) << 32) >> 32));
            });
    }
}
