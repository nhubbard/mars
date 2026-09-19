package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrLbu extends BasicInstruction {
    public InstrLbu() {
        super("lbu $t1,-100($t2)",
              "Load byte unsigned : Set $t1 to zero-extended 8-bit value from effective memory byte address",
              BasicInstructionFormat.I_FORMAT,
              "100100 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              try {
                RegisterFile.updateRegister(
                    operands[0],
                    Globals.memory.getByte(
                            RegisterFile.getValue(operands[2]) + (operands[1] << 16 >> 16))
                        & 0x000000ff);
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
