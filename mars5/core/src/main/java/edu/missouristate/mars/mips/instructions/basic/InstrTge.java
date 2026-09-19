package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTge extends BasicInstruction {
    public InstrTge() {
        super("tge $t1,$t2",
              "Trap if greater or equal : Trap if $t1 is greater than or equal to $t2",
              BasicInstructionFormat.R_FORMAT,
              "000000 fffff sssss ccccc ccccc 110000",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[0]) >= RegisterFile.getValue(operands[1])) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
              }
            });
    }
}
