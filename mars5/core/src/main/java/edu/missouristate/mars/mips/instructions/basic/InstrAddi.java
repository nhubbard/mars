package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrAddi extends BasicInstruction {
    public InstrAddi() {
        super("addi $t1,$t2,-100",
              "Addition immediate with overflow : set $t1 to ($t2 plus signed 16-bit immediate)",
              BasicInstructionFormat.I_FORMAT,
              "001000 sssss fffff tttttttttttttttt",
              statement -> {
              int[] operands = statement.getOperands();
              int add1 = RegisterFile.getValue(operands[1]);
              int add2 = operands[2] << 16 >> 16;
              int sum = add1 + add2;
              
              if ((add1 >= 0 && add2 >= 0 && sum < 0) || (add1 < 0 && add2 < 0 && sum >= 0)) {
                throw new ProcessingException(
                    statement, "arithmetic overflow", Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION);
              }
              RegisterFile.updateRegister(operands[0], sum);
            });
    }
}
