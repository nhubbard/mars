package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSb extends BasicInstruction {
    public InstrSb() {
        super("sb $t1,-100($t2)",
              "Store byte : Store the low-order 8 bits of $t1 into the effective memory byte address",
              BasicInstructionFormat.I_FORMAT,
              "101000 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              try {
                Globals.memory.setByte(
                    RegisterFile.getValue(operands[2]) + (operands[1] << 16 >> 16),
                    RegisterFile.getValue(operands[0]) & 0x000000ff);
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
