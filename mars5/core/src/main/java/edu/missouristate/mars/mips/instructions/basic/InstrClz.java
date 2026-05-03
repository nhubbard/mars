package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrClz extends BasicInstruction {
    public InstrClz() {
        super("clz $t1,$t2",
              "Count number of leading zeroes : Set $t1 to the count of leading zero bits in $t2 starting at most significant bit positio",
              BasicInstructionFormat.R_FORMAT,
              "011100 sssss 00000 fffff 00000 100000",
              statement -> {
              int[] operands = statement.getOperands();
              int value = RegisterFile.getValue(operands[1]);
              int leadingZeros = 0;
              int bitPosition = 31;
              while (Binary.bitValue(value, bitPosition) == 0 && bitPosition >= 0) {
                leadingZeros++;
                bitPosition--;
              }
              RegisterFile.updateRegister(operands[0], leadingZeros);
            });
    }
}
