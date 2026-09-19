package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTne extends BasicInstruction {
    public InstrTne() {
        super("tne $t1,$t2",
              "Trap if not equal : Trap if $t1 is not equal to $t2",
              BasicInstructionFormat.R_FORMAT,
              "000000 fffff sssss ccccc ccccc 110110",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[0]) != RegisterFile.getValue(operands[1])) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
              }
            });
    }
}
