package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSwc1 extends BasicInstruction {
    public InstrSwc1() {
        super("swc1 $f1,-100($t2)",
              "Store word from Coprocesor 1 (FPU) : Store 32 bit value in $f1 to effective memory word address",
              BasicInstructionFormat.I_FORMAT,
              "111001 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              try {
                Globals.memory.setWord(
                    RegisterFile.getValue(operands[2]) + operands[1],
                    Coprocessor1.getValue(operands[0]));
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
