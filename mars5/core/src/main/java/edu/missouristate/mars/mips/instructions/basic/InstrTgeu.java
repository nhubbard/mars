package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTgeu extends BasicInstruction {
    public InstrTgeu() {
        super("tgeu $t1,$t2",
              "Trap if greater or equal unsigned : Trap if $t1 is greater than or equal to $t2 using unsigned comparision",
              BasicInstructionFormat.R_FORMAT,
              "000000 fffff sssss ccccc ccccc 110001",
              statement -> {
              int[] operands = statement.getOperands();
              int first = RegisterFile.getValue(operands[0]);
              int second = RegisterFile.getValue(operands[1]);
              
              
              if ((first >= 0 && second >= 0 || first < 0 && second < 0)
                  ? (first >= second)
                  : (first < 0)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
              }
            });
    }
}
