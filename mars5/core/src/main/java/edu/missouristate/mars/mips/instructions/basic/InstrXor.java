package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrXor extends BasicInstruction {
    public InstrXor() {
        super("xor $t1,$t2,$t3",
              "Bitwise XOR (exclusive OR) : Set $t1 to bitwise XOR of $t2 and $t3",
              BasicInstructionFormat.R_FORMAT,
              "000000 sssss ttttt fffff 00000 100110",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(
                  operands[0],
                  RegisterFile.getValue(operands[1]) ^ RegisterFile.getValue(operands[2]));
            });
    }
}
