package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTltiu extends BasicInstruction {
    public InstrTltiu() {
        super("tltiu $t1,-100",
              "Trap if less than immediate unsigned : Trap if $t1 less than sign-extended 16-bit immediate, unsigned comparison",
              BasicInstructionFormat.I_FORMAT,
              "000001 fffff 01011 ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              int first = RegisterFile.getValue(operands[0]);
              
              int second = operands[1] << 16 >> 16;
              
              
              if ((first >= 0 && second >= 0 || first < 0 && second < 0)
                  ? (first < second)
                  : (first >= 0)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
              }
            });
    }
}
