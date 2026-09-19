package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTlti extends BasicInstruction {
    public InstrTlti() {
        super("tlti $t1,-100",
              "Trap if less than immediate : Trap if $t1 less than sign-extended 16-bit immediate",
              BasicInstructionFormat.I_FORMAT,
              "000001 fffff 01010 ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[0]) < (operands[1] << 16 >> 16)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
              }
            });
    }
}
