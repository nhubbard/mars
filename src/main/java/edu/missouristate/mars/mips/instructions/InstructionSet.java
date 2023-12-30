package edu.missouristate.mars.mips.instructions;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.Settings;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.impl.Nop;
import edu.missouristate.mars.mips.instructions.impl.branches.*;
import edu.missouristate.mars.mips.instructions.impl.compare.*;
import edu.missouristate.mars.mips.instructions.impl.conversion.*;
import edu.missouristate.mars.mips.instructions.impl.math.integer.CountLeadingOnes;
import edu.missouristate.mars.mips.instructions.impl.math.integer.CountLeadingZeroes;
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
import edu.missouristate.mars.mips.instructions.impl.traps.*;
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
        instructionList.add(new FloatAbsoluteValue());

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
        instructionList.add(new DoubleAbsoluteValue());

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
        instructionList.add(new DoubleCompareEqual());
        instructionList.add(new DoubleCompareEqualCustomFlag());
        instructionList.add(new DoubleCompareLessThanOrEqual());
        instructionList.add(new DoubleCompareLessThanOrEqualCustomFlag());
        instructionList.add(new DoubleCompareLess());
        instructionList.add(new DoubleCompareLessCustomFlag());

        // Floating-point conversion instructions
        instructionList.add(new ConvertFloatToDouble());
        instructionList.add(new ConvertWordToDouble());
        instructionList.add(new ConvertDoubleToFloat());
        instructionList.add(new ConvertWordToFloat());
        instructionList.add(new ConvertDoubleToWord());
        instructionList.add(new ConvertFloatToWord());

        // FPU move instructions
        instructionList.add(new MoveDouble());
        instructionList.add(new MoveDoubleIfConditionFlagZeroFalse());
        instructionList.add(new MoveDoubleIfConditionFlagFalse());
        instructionList.add(new MoveDoubleIfConditionFlagZeroTrue());
        instructionList.add(new MoveDoubleIfConditionFlagTrue());
        instructionList.add(new MoveDoubleIfRegisterNotZero());
        instructionList.add(new MoveDoubleIfRegisterZero());
        instructionList.add(new MoveFloat());
        instructionList.add(new MoveFloatIfConditionFlagZeroFalse());
        instructionList.add(new MoveFloatIfConditionFlagFalse());
        instructionList.add(new MoveFloatIfConditionFlagZeroTrue());
        instructionList.add(new MoveFloatIfConditionFlagTrue());
        instructionList.add(new MoveFloatIfRegisterNotZero());
        instructionList.add(new MoveFloatIfRegisterZero());
        instructionList.add(new MoveFromCoprocessor1());
        instructionList.add(new MoveToCoprocessor1());

        // FPU negation instructions
        instructionList.add(new NegateDouble());
        instructionList.add(new NegateFloat());

        // FPU load instructions
        instructionList.add(new LoadWordIntoFPU());
        instructionList.add(new LoadDwordIntoFPU());

        // FPU store instructions
        instructionList.add(new StoreWordFromFPU());
        instructionList.add(new StoreDwordFromFPU());

        // Trap and exception return instructions
        instructionList.add(new TrapIfEqual());
        instructionList.add(new TrapIfEqualImmediate());
        instructionList.add(new TrapNotEqual());
        instructionList.add(new TrapNotEqualImmediate());
        instructionList.add(new TrapIfGreaterOrEqual());
        instructionList.add(new TrapIfGreaterOrEqualUnsigned());
        instructionList.add(new TrapIfGreaterOrEqualImmediate());
        instructionList.add(new TrapIfGreaterOrEqualImmediateUnsigned());
        instructionList.add(new TrapLessThan());
        instructionList.add(new TrapLessThanUnsigned());
        instructionList.add(new TrapLessThanImmediate());
        instructionList.add(new TrapLessThanImmediateUnsigned());
        instructionList.add(new ExceptionReturn());

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