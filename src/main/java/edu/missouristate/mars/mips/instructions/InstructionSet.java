package edu.missouristate.mars.mips.instructions;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.Settings;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.impl.Nop;
import edu.missouristate.mars.mips.instructions.impl.branches.*;
import edu.missouristate.mars.mips.instructions.impl.compare.*;
import edu.missouristate.mars.mips.instructions.impl.count.CountLeadingOnes;
import edu.missouristate.mars.mips.instructions.impl.count.CountLeadingZeroes;
import edu.missouristate.mars.mips.instructions.impl.jumps.*;
import edu.missouristate.mars.mips.instructions.impl.logic.*;
import edu.missouristate.mars.mips.instructions.impl.math.doubleprecision.*;
import edu.missouristate.mars.mips.instructions.impl.math.integer.AddImmediateUnsignedNoOverflow;
import edu.missouristate.mars.mips.instructions.impl.math.integer.AddUnsignedNoOverflow;
import edu.missouristate.mars.mips.instructions.impl.math.integer.SubtractionUnsignedNoOverflow;
import edu.missouristate.mars.mips.instructions.impl.math.integer.*;
import edu.missouristate.mars.mips.instructions.impl.math.singleprecision.*;
import edu.missouristate.mars.mips.instructions.impl.memory.*;
import edu.missouristate.mars.mips.instructions.impl.move.*;
import edu.missouristate.mars.mips.instructions.impl.set.SetLessThan;
import edu.missouristate.mars.mips.instructions.impl.set.SetLessThanImmediate;
import edu.missouristate.mars.mips.instructions.impl.set.SetLessThanImmediateUnsigned;
import edu.missouristate.mars.mips.instructions.impl.set.SetLessThanUnsigned;
import edu.missouristate.mars.mips.instructions.impl.system.BreakWithCode;
import edu.missouristate.mars.mips.instructions.impl.system.BreakWithoutCode;
import edu.missouristate.mars.mips.instructions.impl.system.RunSyscall;
import edu.missouristate.mars.mips.instructions.syscalls.Syscall;
import edu.missouristate.mars.simulator.DelayedBranch;
import edu.missouristate.mars.simulator.Exceptions;
import edu.missouristate.mars.util.Binary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

/**
 * The list of Instruction objects, each of which represents a MIPS instruction.
 * The instruction may either be basic (translates into binary machine code) or
 * extended (translates into a sequence of one or more basic instructions).
 *
 * @author Pete Sanderson and Ken Vollmar
 * @version August 2003-5
 */
@SuppressWarnings("DataFlowIssue")
public class InstructionSet {
    private final ArrayList<Instruction> instructionList;
    private ArrayList<MatchMap> opcodeMatchMaps;
    private SyscallLoader syscallLoader;

    /**
     * Creates a new InstructionSet object.
     */
    public InstructionSet() {
        instructionList = new ArrayList<>();
    }

    /**
     * Retrieve the current instruction set.
     */
    public ArrayList<Instruction> getInstructionList() {
        return instructionList;
    }

    /**
     * Adds all instructions to the set.  A given extended instruction may have
     * more than one Instruction object, depending on how many formats it can have.
     *
     * @see Instruction
     * @see BasicInstruction
     * @see ExtendedInstruction
     */
    public void populate() {
        /* Here is where the parade begins.  Every instruction is added to the set here.*/

        // ////////////////////////////////////   BASIC INSTRUCTIONS START HERE ////////////////////////////////

        instructionList.add(new Nop());

        // Math instructions
        instructionList.add(new Add());
        instructionList.add(new Subtract());
        instructionList.add(new AddImmediate());
        instructionList.add(new AddUnsignedNoOverflow());
        instructionList.add(new SubtractionUnsignedNoOverflow());
        instructionList.add(new AddImmediateUnsignedNoOverflow());
        instructionList.add(new Multiply());
        instructionList.add(new MultiplyUnsigned());
        instructionList.add(new MultiplyNoOverflow());
        instructionList.add(new MultiplyAdd());
        instructionList.add(new MultiplyAddUnsigned());
        instructionList.add(new MultiplySubtract());
        instructionList.add(new MultiplySubtractUnsigned());
        instructionList.add(new Divide());
        instructionList.add(new DivideUnsignedNoOverflow());

        // Math utility instructions
        instructionList.add(new MoveFromHiRegister());
        instructionList.add(new MoveFromLoRegister());
        instructionList.add(new MoveToHiRegister());
        instructionList.add(new MoveToLoRegister());

        // Logic instructions
        instructionList.add(new And());
        instructionList.add(new Or());
        instructionList.add(new AndImmediate());
        instructionList.add(new OrImmediate());
        instructionList.add(new Nor());
        instructionList.add(new Xor());
        instructionList.add(new XorImmediate());
        instructionList.add(new ShiftLeftLogical());
        instructionList.add(new ShiftLeftLogicalVariable());
        instructionList.add(new ShiftRightLogical());
        instructionList.add(new ShiftRightArithmetic());
        instructionList.add(new ShiftRightArithmeticVariable());
        instructionList.add(new ShiftRightLogicalVariable());

        // Load and store instructions
        instructionList.add(new LoadWord());
        instructionList.add(new LoadLink());
        instructionList.add(new LoadWordLeft());
        instructionList.add(new LoadWordRight());
        instructionList.add(new LoadUpperImmediate());
        instructionList.add(new StoreWord());
        instructionList.add(new StoreConditional());
        instructionList.add(new StoreWordLeft());
        instructionList.add(new StoreWordRight());
        instructionList.add(new LoadByte());
        instructionList.add(new LoadHalfWord());
        instructionList.add(new LoadHalfWordUnsigned());
        instructionList.add(new LoadByteUnsigned());
        instructionList.add(new StoreByte());
        instructionList.add(new StoreHalfByte());

        // Branch instructions
        instructionList.add(new BranchIfEqual());
        instructionList.add(new BranchNotEqual());
        instructionList.add(new BranchGreaterEqualZero());
        instructionList.add(new BranchGreaterEqualZeroAndLink());
        instructionList.add(new BranchGreaterThanZero());
        instructionList.add(new BranchLessEqualZero());
        instructionList.add(new BranchLessThanZero());
        instructionList.add(new BranchLessThanZeroAndLink());

        // Set instructions
        instructionList.add(new SetLessThan());
        instructionList.add(new SetLessThanUnsigned());
        instructionList.add(new SetLessThanImmediate());
        instructionList.add(new SetLessThanImmediateUnsigned());

        // Move instructions
        instructionList.add(new MoveConditionalNotZero());
        instructionList.add(new MoveConditionalZero());
        instructionList.add(new MoveIfFloatConditionFlagZeroFalse());
        instructionList.add(new MoveIfFloatConditionFlagFalse());
        instructionList.add(new MoveIfFloatConditionFlagZeroTrue());
        instructionList.add(new MoveIfFloatConditionFlagTrue());

        // System instructions
        instructionList.add(new BreakWithCode());
        instructionList.add(new BreakWithoutCode());
        instructionList.add(new RunSyscall());

        // Jump instructions
        instructionList.add(new Jump());
        instructionList.add(new JumpRegister());
        instructionList.add(new JumpAndLink());
        instructionList.add(new JumpAndLinkRegister());
        instructionList.add(new JumpAndLinkReturnAddress());

        // Count instructions
        instructionList.add(new CountLeadingOnes());
        instructionList.add(new CountLeadingZeroes());

        // Coprocessor 0 (interrupt/exception controller) move instructions
        instructionList.add(new MoveFromCoprocessor0());
        instructionList.add(new MoveToCoprocessor0());

        // Single-precision floating point math instructions
        instructionList.add(new FloatAdd());
        instructionList.add(new FloatSubtract());
        instructionList.add(new FloatMultiply());
        instructionList.add(new FloatDivide());
        instructionList.add(new FloatSquareRoot());
        instructionList.add(new FloatFloorToWord());
        instructionList.add(new FloatCeilingToWord());
        instructionList.add(new RoundFloatToWord());
        instructionList.add(new TruncateFloatToWord());

        // Double-precision floating point math instructions
        instructionList.add(new DoubleAdd());
        instructionList.add(new DoubleSubtract());
        instructionList.add(new DoubleMultiply());
        instructionList.add(new DoubleDivide());
        instructionList.add(new DoubleSquareRoot());
        instructionList.add(new DoubleFloorToWord());
        instructionList.add(new DoubleCeilingToWord());
        instructionList.add(new RoundDoubleToWord());
        instructionList.add(new TruncateDoubleToWord());

        // FPU branch instructions
        instructionList.add(new BranchFPUZeroFlagTrue());
        instructionList.add(new BranchFPUFlagTrue());
        instructionList.add(new BranchFPUZeroFlagFalse());
        instructionList.add(new BranchFPUFlagFalse());

        // Single-precision floating point comparison instructions
        instructionList.add(new FloatCompareEqual());
        instructionList.add(new FloatCompareEqualCustomFlag());
        instructionList.add(new FloatCompareLessThanOrEqual());
        instructionList.add(new FloatCompareLessThanOrEqualCustomFlag());
        instructionList.add(new FloatCompareLess());
        instructionList.add(new FloatCompareLessCustomFlag());

        // Double-precision floating point comparison instructions
        instructionList.add(new BasicInstruction("c.eq.d $f2,$f4", "Compare equal double precision : If $f2 is equal to $f4 (double-precision), set Coprocessor 1 condition flag 0 true else set it false", BasicInstructionFormat.R_FORMAT, "010001 10001 sssss fffff 00000 110010", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            double op1 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[0] + 1), Coprocessor1.getValue(operands[0])));
            double op2 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
            if (op1 == op2) Coprocessor1.setConditionFlag(0);
            else Coprocessor1.clearConditionFlag(0);
        }));
        instructionList.add(new BasicInstruction("c.eq.d 1,$f2,$f4", "Compare equal double precision : If $f2 is equal to $f4 (double-precision), set Coprocessor 1 condition flag specified by immediate to true else set it to false", BasicInstructionFormat.R_FORMAT, "010001 10001 ttttt sssss fff 00 110010", statement -> {
            int[] operands = statement.getOperands();
            if (operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            double op1 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
            double op2 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[2] + 1), Coprocessor1.getValue(operands[2])));
            if (op1 == op2) Coprocessor1.setConditionFlag(operands[0]);
            else Coprocessor1.clearConditionFlag(operands[0]);
        }));
        instructionList.add(new BasicInstruction("c.le.d $f2,$f4", "Compare less or equal double precision : If $f2 is less than or equal to $f4 (double-precision), set Coprocessor 1 condition flag 0 true else set it false", BasicInstructionFormat.R_FORMAT, "010001 10001 sssss fffff 00000 111110", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            double op1 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[0] + 1), Coprocessor1.getValue(operands[0])));
            double op2 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
            if (op1 <= op2) Coprocessor1.setConditionFlag(0);
            else Coprocessor1.clearConditionFlag(0);
        }));
        instructionList.add(new BasicInstruction("c.le.d 1,$f2,$f4", "Compare less or equal double precision : If $f2 is less than or equal to $f4 (double-precision), set Coprocessor 1 condition flag specified by immediate true else set it false", BasicInstructionFormat.R_FORMAT, "010001 10001 ttttt sssss fff 00 111110", statement -> {
            int[] operands = statement.getOperands();
            if (operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            double op1 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
            double op2 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[2] + 1), Coprocessor1.getValue(operands[2])));
            if (op1 <= op2) Coprocessor1.setConditionFlag(operands[0]);
            else Coprocessor1.clearConditionFlag(operands[0]);
        }));
        instructionList.add(new BasicInstruction("c.lt.d $f2,$f4", "Compare less than double precision : If $f2 is less than $f4 (double-precision), set Coprocessor 1 condition flag 0 true else set it false", BasicInstructionFormat.R_FORMAT, "010001 10001 sssss fffff 00000 111100", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            double op1 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[0] + 1), Coprocessor1.getValue(operands[0])));
            double op2 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
            if (op1 < op2) Coprocessor1.setConditionFlag(0);
            else Coprocessor1.clearConditionFlag(0);
        }));
        instructionList.add(new BasicInstruction("c.lt.d 1,$f2,$f4", "Compare less than double precision : If $f2 is less than $f4 (double-precision), set Coprocessor 1 condition flag specified by immediate to true else set it to false", BasicInstructionFormat.R_FORMAT, "010001 10001 ttttt sssss fff 00 111100", statement -> {
            int[] operands = statement.getOperands();
            if (operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            double op1 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
            double op2 = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[2] + 1), Coprocessor1.getValue(operands[2])));
            if (op1 < op2) Coprocessor1.setConditionFlag(operands[0]);
            else Coprocessor1.clearConditionFlag(operands[0]);
        }));

        // FPU helper instructions
        instructionList.add(new BasicInstruction("abs.s $f0,$f1", "Floating point absolute value single precision : Set $f0 to absolute value of $f1, single precision", BasicInstructionFormat.R_FORMAT, "010001 10000 00000 sssss fffff 000101", statement -> {
            int[] operands = statement.getOperands();
            // I need to only clear the high-order bit!
            Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]) & Integer.MAX_VALUE);
        }));
        instructionList.add(new BasicInstruction("abs.d $f2,$f4", "Floating point absolute value double precision : Set $f2 to absolute value of $f4, double precision", BasicInstructionFormat.R_FORMAT, "010001 10001 00000 sssss fffff 000101", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            // I need only to clear the high-order bit of high-word register!
            Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1) & Integer.MAX_VALUE);
            Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
        }));
        instructionList.add(new BasicInstruction("cvt.d.s $f2,$f1", "Convert from single precision to double precision : Set $f2 to double precision equivalent of single precision value in $f1", BasicInstructionFormat.R_FORMAT, "010001 10000 00000 sssss fffff 100001", statement -> {
            int[] operands = getEvenOperand(statement, 0, "first register must be even-numbered");
            // convert single precision in $f1 to double value stored in $f2
            long result = Double.doubleToLongBits(Float.intBitsToFloat(Coprocessor1.getValue(operands[1])));
            Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(result));
            Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(result));
        }));
        instructionList.add(new BasicInstruction("cvt.d.w $f2,$f1", "Convert from word to double precision : Set $f2 to double precision equivalent of 32-bit integer value in $f1", BasicInstructionFormat.R_FORMAT, "010001 10100 00000 sssss fffff 100001", statement -> {
            int[] operands = getEvenOperand(statement, 0, "first register must be even-numbered");
            // convert integer to double (interpret $f1 value as int?)
            long result = Double.doubleToLongBits(Coprocessor1.getValue(operands[1]));
            Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(result));
            Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(result));
        }));
        instructionList.add(new BasicInstruction("cvt.s.d $f1,$f2", "Convert from double precision to single precision : Set $f1 to single precision equivalent of double precision value in $f2", BasicInstructionFormat.R_FORMAT, "010001 10001 00000 sssss fffff 100000", statement -> {
            int[] operands = getEvenOperand(statement, 1, "second register must be even-numbered");
            double val = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
            Coprocessor1.updateRegister(operands[0], Float.floatToIntBits((float) val));
        }));
        instructionList.add(new BasicInstruction("cvt.s.w $f0,$f1", "Convert from word to single precision : Set $f0 to single precision equivalent of 32-bit integer value in $f2", BasicInstructionFormat.R_FORMAT, "010001 10100 00000 sssss fffff 100000", statement -> {
            int[] operands = statement.getOperands();
            // convert integer to single (interpret $f1 value as int?)
            Coprocessor1.updateRegister(operands[0], Float.floatToIntBits((float) Coprocessor1.getValue(operands[1])));
        }));
        instructionList.add(new BasicInstruction("cvt.w.d $f1,$f2", "Convert from double precision to word : Set $f1 to 32-bit integer equivalent of double precision value in $f2", BasicInstructionFormat.R_FORMAT, "010001 10001 00000 sssss fffff 100100", statement -> {
            int[] operands = getEvenOperand(statement, 1, "second register must be even-numbered");
            double val = Double.longBitsToDouble(Binary.twoIntegersToLong(Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
            Coprocessor1.updateRegister(operands[0], (int) val);
        }));
        instructionList.add(new BasicInstruction("cvt.w.s $f0,$f1", "Convert from single precision to word : Set $f0 to 32-bit integer equivalent of single precision value in $f1", BasicInstructionFormat.R_FORMAT, "010001 10000 00000 sssss fffff 100100", statement -> {
            int[] operands = statement.getOperands();
            // convert single precision in $f1 to integer stored in $f0
            Coprocessor1.updateRegister(operands[0], (int) Float.intBitsToFloat(Coprocessor1.getValue(operands[1])));
        }));

        // FPU move instructions
        instructionList.add(new BasicInstruction("mov.d $f2,$f4", "Move floating point double precision : Set double precision $f2 to double precision value in $f4", BasicInstructionFormat.R_FORMAT, "010001 10001 00000 sssss fffff 000110", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
            Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
        }));
        instructionList.add(new BasicInstruction("movf.d $f2,$f4", "Move floating point double precision : If condition flag 0 false, set double precision $f2 to double precision value in $f4", BasicInstructionFormat.R_FORMAT, "010001 10001 000 00 sssss fffff 010001", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            if (Coprocessor1.getConditionFlag(0) == 0) {
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
            }
        }));
        instructionList.add(new BasicInstruction("movf.d $f2,$f4,1", "Move floating point double precision : If condition flag specified by immediate is false, set double precision $f2 to double precision value in $f4", BasicInstructionFormat.R_FORMAT, "010001 10001 ttt 00 sssss fffff 010001", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            if (Coprocessor1.getConditionFlag(operands[2]) == 0) {
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
            }
        }));
        instructionList.add(new BasicInstruction("movt.d $f2,$f4", "Move floating point double precision : If condition flag 0 true, set double precision $f2 to double precision value in $f4", BasicInstructionFormat.R_FORMAT, "010001 10001 000 01 sssss fffff 010001", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            if (Coprocessor1.getConditionFlag(0) == 1) {
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
            }
        }));
        instructionList.add(new BasicInstruction("movt.d $f2,$f4,1", "Move floating point double precision : If condition flag specified by immediate is true, set double precision $f2 to double precision value in $f4e", BasicInstructionFormat.R_FORMAT, "010001 10001 ttt 01 sssss fffff 010001", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            if (Coprocessor1.getConditionFlag(operands[2]) == 1) {
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
            }
        }));
        instructionList.add(new BasicInstruction("movn.d $f2,$f4,$t3", "Move floating point double precision : If $t3 is not zero, set double precision $f2 to double precision value in $f4", BasicInstructionFormat.R_FORMAT, "010001 10001 ttttt sssss fffff 010011", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            if (RegisterFile.getValue(operands[2]) != 0) {
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
            }
        }));
        instructionList.add(new BasicInstruction("movz.d $f2,$f4,$t3", "Move floating point double precision : If $t3 is zero, set double precision $f2 to double precision value in $f4", BasicInstructionFormat.R_FORMAT, "010001 10001 ttttt sssss fffff 010010", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            if (RegisterFile.getValue(operands[2]) == 0) {
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
            }
        }));
        instructionList.add(new BasicInstruction("mov.s $f0,$f1", "Move floating point single precision : Set single precision $f0 to single precision value in $f1", BasicInstructionFormat.R_FORMAT, "010001 10000 00000 sssss fffff 000110", statement -> {
            int[] operands = statement.getOperands();
            Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
        }));
        instructionList.add(new BasicInstruction("movf.s $f0,$f1", "Move floating point single precision : If condition flag 0 is false, set single precision $f0 to single precision value in $f1", BasicInstructionFormat.R_FORMAT, "010001 10000 000 00 sssss fffff 010001", statement -> {
            int[] operands = statement.getOperands();
            if (Coprocessor1.getConditionFlag(0) == 0)
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
        }));
        instructionList.add(new BasicInstruction("movf.s $f0,$f1,1", "Move floating point single precision : If condition flag specified by immediate is false, set single precision $f0 to single precision value in $f1e", BasicInstructionFormat.R_FORMAT, "010001 10000 ttt 00 sssss fffff 010001", statement -> {
            int[] operands = statement.getOperands();
            if (Coprocessor1.getConditionFlag(operands[2]) == 0)
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
        }));
        instructionList.add(new BasicInstruction("movt.s $f0,$f1", "Move floating point single precision : If condition flag 0 is true, set single precision $f0 to single precision value in $f1e", BasicInstructionFormat.R_FORMAT, "010001 10000 000 01 sssss fffff 010001", statement -> {
            int[] operands = statement.getOperands();
            if (Coprocessor1.getConditionFlag(0) == 1)
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
        }));
        instructionList.add(new BasicInstruction("movt.s $f0,$f1,1", "Move floating point single precision : If condition flag specified by immediate is true, set single precision $f0 to single precision value in $f1e", BasicInstructionFormat.R_FORMAT, "010001 10000 ttt 01 sssss fffff 010001", statement -> {
            int[] operands = statement.getOperands();
            if (Coprocessor1.getConditionFlag(operands[2]) == 1)
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
        }));
        instructionList.add(new BasicInstruction("movn.s $f0,$f1,$t3", "Move floating point single precision : If $t3 is not zero, set single precision $f0 to single precision value in $f1", BasicInstructionFormat.R_FORMAT, "010001 10000 ttttt sssss fffff 010011", statement -> {
            int[] operands = statement.getOperands();
            if (RegisterFile.getValue(operands[2]) != 0)
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
        }));
        instructionList.add(new BasicInstruction("movz.s $f0,$f1,$t3", "Move floating point single precision : If $t3 is zero, set single precision $f0 to single precision value in $f1", BasicInstructionFormat.R_FORMAT, "010001 10000 ttttt sssss fffff 010010", statement -> {
            int[] operands = statement.getOperands();
            if (RegisterFile.getValue(operands[2]) == 0)
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
        }));
        instructionList.add(new BasicInstruction("mfc1 $t1,$f1", "Move from Coprocessor 1 (FPU) : Set $t1 to value in Coprocessor 1 register $f1", BasicInstructionFormat.R_FORMAT, "010001 00000 fffff sssss 00000 000000", statement -> {
            int[] operands = statement.getOperands();
            RegisterFile.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
        }));
        instructionList.add(new BasicInstruction("mtc1 $t1,$f1", "Move to Coprocessor 1 (FPU) : Set Coprocessor 1 register $f1 to value in $t1", BasicInstructionFormat.R_FORMAT, "010001 00100 fffff sssss 00000 000000", statement -> {
            int[] operands = statement.getOperands();
            Coprocessor1.updateRegister(operands[1], RegisterFile.getValue(operands[0]));
        }));

        // More FPU utility instructions
        instructionList.add(new BasicInstruction("neg.d $f2,$f4", "Floating point negate double precision : Set double precision $f2 to negation of double precision value in $f4", BasicInstructionFormat.R_FORMAT, "010001 10001 00000 sssss fffff 000111", statement -> {
            int[] operands = statement.getOperands();
            if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
            }
            // flip the sign bit of the second register (high-order word) of the pair
            int value = Coprocessor1.getValue(operands[1] + 1);
            Coprocessor1.updateRegister(operands[0] + 1, ((value < 0) ? (value & Integer.MAX_VALUE) : (value | Integer.MIN_VALUE)));
            Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
        }));
        instructionList.add(new BasicInstruction("neg.s $f0,$f1", "Floating point negate single precision : Set single precision $f0 to negation of single precision value in $f1", BasicInstructionFormat.R_FORMAT, "010001 10000 00000 sssss fffff 000111", statement -> {
            int[] operands = statement.getOperands();
            int value = Coprocessor1.getValue(operands[1]);
            // flip the sign bit
            Coprocessor1.updateRegister(operands[0], ((value < 0) ? (value & Integer.MAX_VALUE) : (value | Integer.MIN_VALUE)));
        }));

        // FPU load instructions
        instructionList.add(new BasicInstruction("lwc1 $f1,-100($t2)", "Load word into Coprocessor 1 (FPU) : Set $f1 to 32-bit value from effective memory word address", BasicInstructionFormat.I_FORMAT, "110001 ttttt fffff ssssssssssssssss", statement -> {
            int[] operands = statement.getOperands();
            try {
                Coprocessor1.updateRegister(operands[0], Globals.memory.getWord(RegisterFile.getValue(operands[2]) + operands[1]));
            } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
            }
        }));
        instructionList.add(// no printed reference, got opcode from SPIM
                new BasicInstruction("ldc1 $f2,-100($t2)", "Load double word Coprocessor 1 (FPU)) : Set $f2 to 64-bit value from effective memory double-word address", BasicInstructionFormat.I_FORMAT, "110101 ttttt fffff ssssssssssssssss", statement -> {
                    int[] operands = getEvenOperand(statement, 0, "first register must be even-numbered");
                    // IF statement added by DPS 13-July-2011.
                    if (!Memory.doubleWordAligned(RegisterFile.getValue(operands[2]) + operands[1])) {
                        throw new ProcessingException(statement, new AddressErrorException("address not aligned on double-word boundary ", Exceptions.ADDRESS_EXCEPTION_LOAD, RegisterFile.getValue(operands[2]) + operands[1]));
                    }

                    try {
                        Coprocessor1.updateRegister(operands[0], Globals.memory.getWord(RegisterFile.getValue(operands[2]) + operands[1]));
                        Coprocessor1.updateRegister(operands[0] + 1, Globals.memory.getWord(RegisterFile.getValue(operands[2]) + operands[1] + 4));
                    } catch (AddressErrorException e) {
                        throw new ProcessingException(statement, e);
                    }
                }));

        // FPU store instructions
        instructionList.add(new BasicInstruction("swc1 $f1,-100($t2)", "Store word from Coprocessor 1 (FPU) : Store 32 bit value in $f1 to effective memory word address", BasicInstructionFormat.I_FORMAT, "111001 ttttt fffff ssssssssssssssss", statement -> {
            int[] operands = statement.getOperands();
            try {
                Globals.memory.setWord(RegisterFile.getValue(operands[2]) + operands[1], Coprocessor1.getValue(operands[0]));
            } catch (AddressErrorException e) {
                throw new ProcessingException(statement, e);
            }
        }));
        instructionList.add( // no printed reference, got opcode from SPIM
                new BasicInstruction("sdc1 $f2,-100($t2)", "Store double word from Coprocessor 1 (FPU)) : Store 64 bit value in $f2 to effective memory double-word address", BasicInstructionFormat.I_FORMAT, "111101 ttttt fffff ssssssssssssssss", statement -> {
                    int[] operands = getEvenOperand(statement, 0, "first register must be even-numbered");
                    // IF statement added by DPS 13-July-2011.
                    if (!Memory.doubleWordAligned(RegisterFile.getValue(operands[2]) + operands[1])) {
                        throw new ProcessingException(statement, new AddressErrorException("address not aligned on double-word boundary ", Exceptions.ADDRESS_EXCEPTION_STORE, RegisterFile.getValue(operands[2]) + operands[1]));
                    }
                    try {
                        Globals.memory.setWord(RegisterFile.getValue(operands[2]) + operands[1], Coprocessor1.getValue(operands[0]));
                        Globals.memory.setWord(RegisterFile.getValue(operands[2]) + operands[1] + 4, Coprocessor1.getValue(operands[0] + 1));
                    } catch (AddressErrorException e) {
                        throw new ProcessingException(statement, e);
                    }
                }));

        // Trap and exception return instructions
        instructionList.add(new BasicInstruction("teq $t1,$t2", "Trap if equal : Trap if $t1 is equal to $t2", BasicInstructionFormat.R_FORMAT, "000000 fffff sssss 00000 00000 110100", statement -> {
            int[] operands = statement.getOperands();
            if (RegisterFile.getValue(operands[0]) == RegisterFile.getValue(operands[1])) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("teqi $t1,-100", "Trap if equal to immediate : Trap if $t1 is equal to sign-extended 16 bit immediate", BasicInstructionFormat.I_FORMAT, "000001 fffff 01100 ssssssssssssssss", statement -> {
            int[] operands = statement.getOperands();
            if (RegisterFile.getValue(operands[0]) == (operands[1] << 16 >> 16)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("tne $t1,$t2", "Trap if not equal : Trap if $t1 is not equal to $t2", BasicInstructionFormat.R_FORMAT, "000000 fffff sssss 00000 00000 110110", statement -> {
            int[] operands = statement.getOperands();
            if (RegisterFile.getValue(operands[0]) != RegisterFile.getValue(operands[1])) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("tnei $t1,-100", "Trap if not equal to immediate : Trap if $t1 is not equal to sign-extended 16 bit immediate", BasicInstructionFormat.I_FORMAT, "000001 fffff 01110 ssssssssssssssss", statement -> {
            int[] operands = statement.getOperands();
            if (RegisterFile.getValue(operands[0]) != (operands[1] << 16 >> 16)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("tge $t1,$t2", "Trap if greater or equal : Trap if $t1 is greater than or equal to $t2", BasicInstructionFormat.R_FORMAT, "000000 fffff sssss 00000 00000 110000", statement -> {
            int[] operands = statement.getOperands();
            if (RegisterFile.getValue(operands[0]) >= RegisterFile.getValue(operands[1])) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("tgeu $t1,$t2", "Trap if greater or equal unsigned : Trap if $t1 is greater than or equal to $t2 using unsigned comparison", BasicInstructionFormat.R_FORMAT, "000000 fffff sssss 00000 00000 110001", statement -> {
            int[] operands = statement.getOperands();
            int first = RegisterFile.getValue(operands[0]);
            int second = RegisterFile.getValue(operands[1]);
            // if signs are the same, do a straight compare; if signs differ & first negative then first greater else second
            if ((first >= 0 && second >= 0 || first < 0 && second < 0) ? (first >= second) : (first < 0)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("tgei $t1,-100", "Trap if greater than or equal to immediate : Trap if $t1 greater than or equal to sign-extended 16 bit immediate", BasicInstructionFormat.I_FORMAT, "000001 fffff 01000 ssssssssssssssss", statement -> {
            int[] operands = statement.getOperands();
            if (RegisterFile.getValue(operands[0]) >= (operands[1] << 16 >> 16)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("tgeiu $t1,-100", "Trap if greater or equal to immediate unsigned : Trap if $t1 greater than or equal to sign-extended 16 bit immediate, unsigned comparison", BasicInstructionFormat.I_FORMAT, "000001 fffff 01001 ssssssssssssssss", statement -> {
            int[] operands = statement.getOperands();
            int first = RegisterFile.getValue(operands[0]);
            // 16 bit immediate value in operands[1] is sign-extended
            int second = operands[1] << 16 >> 16;
            // if the signs are the same, do a straight comparison; if signs differ & first negative then first greater else second
            if ((first >= 0 && second >= 0 || first < 0 && second < 0) ? (first >= second) : (first < 0)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("tlt $t1,$t2", "Trap if less than: Trap if $t1 less than $t2", BasicInstructionFormat.R_FORMAT, "000000 fffff sssss 00000 00000 110010", statement -> {
            int[] operands = statement.getOperands();
            if (RegisterFile.getValue(operands[0]) < RegisterFile.getValue(operands[1])) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("tltu $t1,$t2", "Trap if less than unsigned : Trap if $t1 less than $t2, unsigned comparison", BasicInstructionFormat.R_FORMAT, "000000 fffff sssss 00000 00000 110011", statement -> {
            int[] operands = statement.getOperands();
            int first = RegisterFile.getValue(operands[0]);
            int second = RegisterFile.getValue(operands[1]);
            // if signs are the same, do a straight compare; if signs differ & first is positive, then first is less else second
            if ((first >= 0 && second >= 0 || first < 0 && second < 0) ? (first < second) : (first >= 0)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("tlti $t1,-100", "Trap if less than immediate : Trap if $t1 less than sign-extended 16-bit immediate", BasicInstructionFormat.I_FORMAT, "000001 fffff 01010 ssssssssssssssss", statement -> {
            int[] operands = statement.getOperands();
            if (RegisterFile.getValue(operands[0]) < (operands[1] << 16 >> 16)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("tltiu $t1,-100", "Trap if less than immediate unsigned : Trap if $t1 less than sign-extended 16-bit immediate, unsigned comparison", BasicInstructionFormat.I_FORMAT, "000001 fffff 01011 ssssssssssssssss", statement -> {
            int[] operands = statement.getOperands();
            int first = RegisterFile.getValue(operands[0]);
            // 16 bit immediate value in operands[1] is sign-extended
            int second = operands[1] << 16 >> 16;
            // if signs are the same, do a straight compare; if signs differ & first is positive, then first is less else second
            if ((first >= 0 && second >= 0 || first < 0 && second < 0) ? (first < second) : (first >= 0)) {
                throw new ProcessingException(statement, "trap", Exceptions.TRAP_EXCEPTION);
            }
        }));
        instructionList.add(new BasicInstruction("eret", "Exception return : Set Program Counter to Coprocessor 0 EPC register value, set Coprocessor Status register bit 1 (exception level) to zero", BasicInstructionFormat.R_FORMAT, "010000 1 0000000000000000000 011000", statement -> {
            // set EXL bit (bit 1) in Status register to 0 and set PC to EPC
            Coprocessor0.updateRegister(Coprocessor0.STATUS, Binary.clearBit(Coprocessor0.getValue(Coprocessor0.STATUS), Coprocessor0.EXCEPTION_LEVEL));
            RegisterFile.setProgramCounter(Coprocessor0.getValue(Coprocessor0.EPC));
        }));

        ////////////// READ PSEUDO-INSTRUCTION SPECS FROM DATA FILE AND ADD //////////////////////
        addPseudoInstructions();

        ////////////// GET AND CREATE LIST OF SYSCALL FUNCTION OBJECTS ////////////////////
        syscallLoader = new SyscallLoader();
        syscallLoader.loadSyscalls();

        // Initialization step.  Create a token list for each instruction example. The parser uses this
        //  to determine user program correct syntax.
        for (Instruction instruction : instructionList) {
            instruction.createExampleTokenList();
        }

        HashMap<Integer, HashMap<Integer, Instruction>> maskMap = new HashMap<>();
        ArrayList<MatchMap> matchMaps = new ArrayList<>();
        for (Instruction rawInstr : instructionList) {
            if (rawInstr instanceof BasicInstruction basic) {
                Integer mask = basic.getOpcodeMask();
                Integer match = basic.getOpcodeMatch();
                HashMap<Integer, Instruction> matchMap = maskMap.get(mask);
                if (matchMap == null) {
                    matchMap = new HashMap<>();
                    maskMap.put(mask, matchMap);
                    matchMaps.add(new MatchMap(mask, matchMap));
                }
                matchMap.put(match, basic);
            }
        }
        Collections.sort(matchMaps);
        this.opcodeMatchMaps = matchMaps;
    }

    private static void compareUnsigned(int[] operands, int first, int second) {
        if (first >= 0 && second >= 0 || first < 0 && second < 0) {
            RegisterFile.updateRegister(operands[0], (first < second) ? 1 : 0);
        } else {
            RegisterFile.updateRegister(operands[0], (first >= 0) ? 1 : 0);
        }
    }

    private static int @NotNull [] getEvenOperand(ProgramStatement statement, int x, String m) throws ProcessingException {
        int[] operands = statement.getOperands();
        if (operands[x] % 2 == 1) throw new ProcessingException(statement, m);
        return operands;
    }

    private static long getLongSqrt(double value) {
        long longSqrt;
        if (value < 0.0) {
            // This is subject to refinement later.  Release 4.0 defines the floor, ceil, trunc, and round
            // to act silently rather than raise Invalid Operation exception, so sqrt should do the
            // same.  An intermediate step would be to define a setting for FCSR Invalid Operation
            // flag, but the best solution is to simulate the FCSR register itself.
            // FCSR = Floating point unit Control and Status Register.  DPS 10-Aug-2010
            longSqrt = Double.doubleToLongBits(Double.NaN);
            //throw new ProcessingException(statement, "Invalid Operation: sqrt of negative number");
        } else {
            longSqrt = Double.doubleToLongBits(Math.sqrt(value));
        }
        return longSqrt;
    }

    @Nullable
    public BasicInstruction findByBinaryCode(int binaryInstr) {
        ArrayList<MatchMap> matchMaps = this.opcodeMatchMaps;
        for (MatchMap matchMap : matchMaps) {
            BasicInstruction ret = matchMap.find(binaryInstr);
            if (ret != null) return ret;
        }
        return null;
    }

    /*  METHOD TO ADD PSEUDO-INSTRUCTIONS
     */

    private void addPseudoInstructions() {
        InputStream is;
        BufferedReader in = null;
        try {
            // leading "/" prevents package name being prepended to filepath.
            is = this.getClass().getResourceAsStream("/PseudoOps.txt");
            in = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)));
        } catch (NullPointerException e) {
            System.out.println("Error: MIPS pseudo-instruction file PseudoOps.txt not found.");
            System.exit(0);
        }
        try {
            String line;
            String pseudoOp;
            StringBuilder template;
            String firstTemplate;
            String token;
            String description;
            StringTokenizer tokenizer;
            while ((line = in.readLine()) != null) {
                // skip over: comment lines, empty lines, lines starting with blank.
                if (!line.startsWith("#") && !line.startsWith(" ") && !line.isEmpty()) {
                    description = "";
                    tokenizer = new StringTokenizer(line, "\t");
                    pseudoOp = tokenizer.nextToken();
                    template = new StringBuilder();
                    firstTemplate = null;
                    while (tokenizer.hasMoreTokens()) {
                        token = tokenizer.nextToken();
                        if (token.startsWith("#")) {
                            // Optional description must be the last token in the line.
                            description = token.substring(1);
                            break;
                        }
                        if (token.startsWith("COMPACT")) {
                            // has second template for Compact (16-bit) memory config -- added DPS 3 Aug 2009
                            firstTemplate = template.toString();
                            template = new StringBuilder();
                            continue;
                        }
                        template.append(token);
                        if (tokenizer.hasMoreTokens()) {
                            template.append("\n");
                        }
                    }
                    ExtendedInstruction inst = (firstTemplate == null) ? new ExtendedInstruction(pseudoOp, template.toString(), description) : new ExtendedInstruction(pseudoOp, firstTemplate, template.toString(), description);
                    instructionList.add(inst);
                    //if (firstTemplate != null) System.out.println("\npseudoOp: "+pseudoOp+"\ndefault template:\n"+firstTemplate+"\ncompact template:\n"+template);
                }
            }
            in.close();
        } catch (IOException ioe) {
            System.out.println("Internal Error: MIPS pseudo-instructions could not be loaded.");
            System.exit(0);
        } catch (Exception ioe) {
            System.out.println("Error: Invalid MIPS pseudo-instruction specification.");
            System.exit(0);
        }

    }

    /**
     * Given an operator mnemonic, will return the corresponding Instruction object(s)
     * from the instruction set.  Uses straight linear search technique.
     *
     * @param name operator mnemonic (e.g. addi, sw,...)
     * @return list of corresponding Instruction object(s), or null if not found.
     */
    public ArrayList<Instruction> matchOperator(String name) {
        ArrayList<Instruction> matchingInstructions = null;
        // Linear search for now
        for (Instruction instruction : instructionList) {
            if (instruction.getName().equalsIgnoreCase(name)) {
                if (matchingInstructions == null) matchingInstructions = new ArrayList<>();
                matchingInstructions.add(instruction);
            }
        }
        return matchingInstructions;
    }


    /**
     * Given a string, will return the Instruction object(s) from the instruction
     * set whose operator mnemonic prefix matches it.  Case-insensitive.  For example,
     * "s" will match "sw", "sh", "sb", etc. Uses straight linear search technique.
     *
     * @param name a string
     * @return list of matching Instruction object(s), or null if none match.
     */
    public ArrayList<Instruction> prefixMatchOperator(String name) {
        ArrayList<Instruction> matchingInstructions = null;
        // Linear search for now
        if (name != null) {
            for (Instruction instruction : instructionList) {
                if (instruction.getName().toLowerCase().startsWith(name.toLowerCase())) {
                    if (matchingInstructions == null) matchingInstructions = new ArrayList<>();
                    matchingInstructions.add(instruction);
                }
            }
        }
        return matchingInstructions;
    }

    /*
     * Method to find and invoke a syscall given its service number.  An object
     * represents each syscall function in an array list.  Each object is of
     * a class that implements Syscall or extends AbstractSyscall.
     */
    private void findAndSimulateSyscall(int number, ProgramStatement statement) throws ProcessingException {
        Syscall service = syscallLoader.findSyscall(number);
        if (service != null) {
            service.simulate(statement);
            return;
        }
        throw new ProcessingException(statement, STR."invalid or unimplemented syscall service: \{number} ", Exceptions.SYSCALL_EXCEPTION);
    }

    /*
     * Method to process a successful branch condition. DO NOT USE THIS WITH JUMP
     * INSTRUCTIONS!  The branch operand is a relative displacement in words,
     * whereas the jump operand is an absolute address in bytes.
     *
     * The parameter is displacement operand from instruction.
     *
     * Handles delayed branching if that setting is enabled.
     */
    // 4 January 2008 DPS: The subtraction of 4 bytes (instruction length) after
    // the shift has been removed.  It is left in as the commented-out code below.
    // This has the effect of always branching as if delayed branching is enabled,
    // even if it isn't.  This mod must work in conjunction with
    // ProgramStatement.java, buildBasicStatementFromBasicInstruction() method near
    // the bottom (currently line 194, heavily commented).
    private void processBranch(int displacement) {
        if (Globals.getSettings().getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED)) {
            // Register the branch target address (absolute byte address).
            DelayedBranch.register(RegisterFile.getProgramCounter().getValue() + (displacement << 2));
        } else {
            // Decrement needed because PC has already been incremented
            RegisterFile.setProgramCounter(RegisterFile.getProgramCounter().getValue() + (displacement << 2)); // - Instruction.INSTRUCTION_LENGTH);
        }
    }

    /**
     * Method to process a jump.
     * DO NOT USE THIS WITH BRANCH INSTRUCTIONS!
     * The branch operand is a relative displacement in words,
     * whereas the jump operand is an absolute address in bytes.
     * Handles delayed branching if that setting is enabled.
     *
     * @param targetAddress The jump target absolute byte address.
     */
    private void processJump(int targetAddress) {
        if (Globals.getSettings().getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED)) {
            DelayedBranch.register(targetAddress);
        } else {
            RegisterFile.setProgramCounter(targetAddress);
        }
    }

    /**
     * Method to process storing of a return address in the given
     * register.  This is used only by the "and link"
     * instructions: jal, jalr, bltzal, bgezal.  If delayed branching
     * setting is off, the return address is the address of the
     * next instruction (e.g., the current PC value).  If on, the
     * return address is the instruction following that, to skip over
     * the delay slot.
     *
     * @param register The register number to receive the return address.
     */
    private void processReturnAddress(int register) {
        RegisterFile.updateRegister(register, RegisterFile.getProgramCounter().getValue() + ((Globals.getSettings().getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED)) ? Instruction.INSTRUCTION_LENGTH : 0));
    }

    private static class MatchMap implements Comparable<MatchMap> {
        private final int mask;
        private final int maskLength; // number of bits in mask
        private final HashMap<Integer, Instruction> matchMap;

        public MatchMap(int mask, HashMap<Integer, Instruction> matchMap) {
            this.mask = mask;
            this.matchMap = matchMap;

            int k = 0;
            int n = mask;
            while (n != 0) {
                k++;
                n &= n - 1;
            }
            this.maskLength = k;
        }

        public boolean equals(Object o) {
            return o instanceof MatchMap && mask == ((MatchMap) o).mask;
        }

        public int compareTo(@NotNull MatchMap other) {
            int d = other.maskLength - this.maskLength;
            if (d == 0) d = this.mask - other.mask;
            return d;
        }

        public BasicInstruction find(int instr) {
            int match = instr & mask;
            return (BasicInstruction) matchMap.get(match);
        }
    }
}