package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrLh extends BasicInstruction {
    public InstrLh() {
        super("lh $t1,-100($t2)",
              "Load halfword : Set $t1 to sign-extended 16-bit value from effective memory halfword address",
              BasicInstructionFormat.I_FORMAT,
              "100001 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              try {
                RegisterFile.updateRegister(
                    operands[0],
                    Globals.memory.getHalf(
                            RegisterFile.getValue(operands[2]) + (operands[1] << 16 >> 16))
                        << 16
                        >> 16);
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
