package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSh extends BasicInstruction {
    public InstrSh() {
        super("sh $t1,-100($t2)",
              "Store halfword : Store the low-order 16 bits of $t1 into the effective memory halfword address",
              BasicInstructionFormat.I_FORMAT,
              "101001 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              try {
                Globals.memory.setHalf(
                    RegisterFile.getValue(operands[2]) + (operands[1] << 16 >> 16),
                    RegisterFile.getValue(operands[0]) & 0x0000ffff);
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
