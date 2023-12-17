package edu.missouristate.mars.mips.instructions;

/**
 * These are the MIPS-defined formats of basic machine instructions.  The R-format indicates
 * the instruction works only with registers.  The I-format indicates the instruction
 * works with an immediate value (e.g., constant).  The J-format indicates this is a Jump
 * instruction.  We define the I-branch-format to indicate this is
 * a Branch instruction, specifically to distinguish immediate
 * values used as target addresses.
 *
 * @author Pete Sanderson
 * @version August 2003
 */
public class BasicInstructionFormat {
    public static final BasicInstructionFormat R_FORMAT = new BasicInstructionFormat();
    public static final BasicInstructionFormat I_FORMAT = new BasicInstructionFormat();
    public static final BasicInstructionFormat I_BRANCH_FORMAT = new BasicInstructionFormat();
    public static final BasicInstructionFormat J_FORMAT = new BasicInstructionFormat();

    // private default constructor prevents objects of this class other than those above.
    private BasicInstructionFormat() {
    }
}
