package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrAbsD extends BasicInstruction {
    public InstrAbsD() {
        super("abs.d $f2,$f4",
              "Floating point absolute value double precision : Set $f2 to absolute value of $f4, double precision",
              BasicInstructionFormat.R_FORMAT,
              "010001 10001 00000 sssss fffff 000101",
              statement -> {
              int[] operands = statement.getOperands();
              if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
              }
              
              Coprocessor1.updateRegister(
                  operands[0] + 1, Coprocessor1.getValue(operands[1] + 1) & Integer.MAX_VALUE);
              Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
            });
    }
}
