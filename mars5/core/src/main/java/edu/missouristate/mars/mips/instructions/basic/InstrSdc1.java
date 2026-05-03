package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSdc1 extends BasicInstruction {
    public InstrSdc1() {
        super("sdc1 $f2,-100($t2)",
              "Store double word from Coprocessor 1 (FPU)) : Store 64 bit value in $f2 to effective memory doubleword address",
              BasicInstructionFormat.I_FORMAT,
              "111101 ttttt fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              if (operands[0] % 2 == 1) {
                throw new ProcessingException(statement, "first register must be even-numbered");
              }
              
              if (!Memory.doublewordAligned(RegisterFile.getValue(operands[2]) + operands[1])) {
                throw new ProcessingException(
                    statement,
                    new AddressErrorException(
                        "address not aligned on doubleword boundary ",
                        Exceptions.ADDRESS_EXCEPTION_STORE,
                        RegisterFile.getValue(operands[2]) + operands[1]));
              }
              try {
                Globals.memory.setWord(
                    RegisterFile.getValue(operands[2]) + operands[1],
                    Coprocessor1.getValue(operands[0]));
                Globals.memory.setWord(
                    RegisterFile.getValue(operands[2]) + operands[1] + 4,
                    Coprocessor1.getValue(operands[0] + 1));
              } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
              }
            });
    }
}
