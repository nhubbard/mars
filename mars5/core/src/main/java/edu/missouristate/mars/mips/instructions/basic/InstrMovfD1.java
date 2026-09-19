package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMovfD1 extends BasicInstruction {
    public InstrMovfD1() {
        super("movf.d $f2,$f4",
              "Move floating point double precision : If condition flag 0 false, set double precision $f2 to double precision value in $f4",
              BasicInstructionFormat.R_FORMAT,
              "010001 10001 000 00 sssss fffff 010001",
              statement -> {
              int[] operands = statement.getOperands();
              if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
              }
              if (Coprocessor1.getConditionFlag(0) == 0) {
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                Coprocessor1.updateRegister(
                    operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
              }
            });
    }
}
