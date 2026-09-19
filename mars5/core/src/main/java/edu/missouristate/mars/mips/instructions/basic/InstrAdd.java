package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrAdd extends BasicInstruction {
    public InstrAdd() {
        super("add $t1,$t2,$t3",
              "Addition with overflow : set $t1 to ($t2 plus $t3)",
              BasicInstructionFormat.R_FORMAT,
              "000000 sssss ttttt fffff 00000 100000",
              statement -> {
              int[] operands = statement.getOperands();
              int add1 = RegisterFile.getValue(operands[1]);
              int add2 = RegisterFile.getValue(operands[2]);
              int sum = add1 + add2;
              
              if ((add1 >= 0 && add2 >= 0 && sum < 0) || (add1 < 0 && add2 < 0 && sum >= 0)) {
                throw new ProcessingException(
                    statement, "arithmetic overflow", Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION);
              }
              RegisterFile.updateRegister(operands[0], sum);
            });
    }
}
