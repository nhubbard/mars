package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTeqi extends BasicInstruction {
    public InstrTeqi() {
        super("teqi $t1,-100",
              "Trap if equal to immediate : Trap if $t1 is equal to sign-extended 16 bit immediate",
              BasicInstructionFormat.I_FORMAT,
              "000001 fffff 01100 ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[0]) == (operands[1] << 16 >> 16)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
              }
            });
    }
}
