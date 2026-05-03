package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrLl extends BasicInstruction {
    public InstrLl() {
        super("ll $t1,-100($t2)",
              "Load linked : Paired with Store Conditional (sc) to perform atomic read-modify-write.  Treated as equivalent to Load Word (lw) because MARS does not simulate multiple processors.",
              BasicInstructionFormat.I_FORMAT,
              "110000 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              try {
                RegisterFile.updateRegister(
                    operands[0],
                    Globals.memory.getWord(RegisterFile.getValue(operands[2]) + operands[1]));
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
