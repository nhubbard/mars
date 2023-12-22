package edu.missouristate.mars;

import edu.missouristate.mars.assembler.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.mips.hardware.*;

import java.util.*;
import java.io.*;
import javax.swing.*;

/**
 * Internal representations of MIPS program.
 * Connects the source code, parsed tokens, and machine code.
 * Having all these structures available facilitates construction of good messages,
 * debugging, and easy simulation.
 *
 * @author Pete Sanderson
 * @version August 2003
 **/
public class MIPSProgram {
    // See explanation of method inSteppedExecution() below.
    private boolean steppedExecution = false;
    private String filename;
    private ArrayList<String> sourceList;
    private ArrayList<TokenList> tokenList;
    private ArrayList<ProgramStatement> parsedList;
    private ArrayList<ProgramStatement> machineList;
    private BackStepper backStepper;
    private SymbolTable localSymbolTable;
    private MacroPool macroPool;
    private ArrayList<SourceLine> sourceLineList;
    private Tokenizer tokenizer;

    /**
     * Produces a list of source statements that comprise the program.
     *
     * @return ArrayList of String.  Each String is one line of MIPS source code.
     **/
    public ArrayList<String> getSourceList() {
        return sourceList;
    }

    /**
     * Set a list of source statements that comprise the program.
     *
     * @param sourceLineList ArrayList of SourceLine.
     *                       Each SourceLine represents one line of MIPS source code.
     **/
    public void setSourceLineList(ArrayList<SourceLine> sourceLineList) {
        this.sourceLineList = sourceLineList;
        sourceList = new ArrayList<>();
        for (SourceLine sl : sourceLineList) {
            sourceList.add(sl.getSource());
        }
    }

    /**
     * Retrieve a list of source statements that comprise the program.
     *
     * @return ArrayList of SourceLine.
     * Each SourceLine represents one line of MIPS source cod
     **/
    public ArrayList<SourceLine> getSourceLineList() {
        return this.sourceLineList;
    }

    /**
     * Produces name of associated source code file.
     *
     * @return File name as String.
     **/
    public String getFilename() {
        return filename;
    }

    /**
     * Produces a list of tokens that comprise the program.
     *
     * @return ArrayList of TokenList.
     * Each TokenList is a list of tokens generated by the corresponding line of MIPS source code.
     * @see TokenList
     **/
    public ArrayList<TokenList> getTokenList() {
        return tokenList;
    }

    /**
     * Retrieves Tokenizer for this program
     *
     * @return Tokenizer
     **/
    public Tokenizer getTokenizer() {
        return tokenizer;
    }

    /**
     * Produces a new empty list to hold parsed source code statements.
     *
     * @return ArrayList of ProgramStatement.  Each ProgramStatement represents a parsed
     * MIPS statement.
     * @see ProgramStatement
     **/
    public ArrayList<ProgramStatement> createParsedList() {
        parsedList = new ArrayList<>();
        return parsedList;
    }

    /**
     * Produces an existing list of parsed source code statements.
     *
     * @return ArrayList of ProgramStatement.  Each ProgramStatement represents a parsed
     * MIPS statement.
     * @see ProgramStatement
     **/
    public ArrayList<ProgramStatement> getParsedList() {
        return parsedList;
    }

    /**
     * Produces a list of machine statements that are assembled from the program.
     *
     * @return ArrayList of ProgramStatement.  Each ProgramStatement represents an assembled
     * basic MIPS instruction.
     * @see ProgramStatement
     **/
    public ArrayList<ProgramStatement> getMachineList() {
        return machineList;
    }

    /**
     * Returns BackStepper associated with this program.  It is created upon successful assembly.
     *
     * @return BackStepper object, null if there is none.
     **/
    public BackStepper getBackStepper() {
        return backStepper;
    }

    /**
     * Returns SymbolTable associated with this program.  It is created at assembly time,
     * and stores local labels (those not declared using .globl directive).
     **/
    public SymbolTable getLocalSymbolTable() {
        return localSymbolTable;
    }

    /**
     * Returns status of BackStepper associated with this program.
     *
     * @return true if enabled, false if disabled or non-existent.
     **/
    public boolean backSteppingEnabled() {
        return (backStepper != null && backStepper.enabled());
    }

    /**
     * Produces specified line of MIPS source program.
     *
     * @param i Line number of MIPS source program to get.  Line 1 is the first line.
     * @return Returns specified line of MIPS source.  If outside the line range,
     * it returns null.
     * Line 1 is the first line.
     **/
    public String getSourceLine(int i) {
        if ((i >= 1) && (i <= sourceList.size()))
            return sourceList.get(i - 1);
        else
            return null;
    }

    /**
     * Reads MIPS source code from file into structure.  Will always read from file.
     * It is the GUI's responsibility to ensure that source edits are written to file
     * when the user selects the compile or run/step options.
     *
     * @param file String containing the name of MIPS source code file.
     * @throws ProcessingException Will throw exception if there is any problem reading the file.
     **/
    public void readSource(String file) throws ProcessingException {
        this.filename = file;
        this.sourceList = new ArrayList<>();
        ErrorList errors;
        BufferedReader inputFile;
        String line;
        int lengthSoFar = 0;
        try {
            inputFile = new BufferedReader(new FileReader(file));
            line = inputFile.readLine();
            while (line != null) {
                sourceList.add(line);
                line = inputFile.readLine();
            }
        } catch (Exception e) {
            errors = new ErrorList();
            errors.add(new ErrorMessage((MIPSProgram) null, 0, 0, e.toString()));
            throw new ProcessingException(errors);
        }
    }

    /**
     * Tokenizes the MIPS source program. The program must have already been read from the file.
     *
     * @throws ProcessingException Will throw exception if errors occur while tokenizing.
     **/
    public void tokenize() throws ProcessingException {
        this.tokenizer = new Tokenizer();
        this.tokenList = tokenizer.tokenize(this);
        this.localSymbolTable = new SymbolTable(this.filename); // prepare for assembly
    }

    /**
     * Prepares the given list of files for assembly.  This involves
     * reading and tokenizing all the source files.  There may be only one.
     *
     * @param filenames        ArrayList containing the source file name(s) in no particular order
     * @param leadFilename     String containing the name of source file that needs to go first and
     *                         will be represented by "this" MIPSProgram object.
     * @param exceptionHandler String containing the name of source file containing exception
     *                         handler.  This will be assembled first, even ahead of leadFilename, to allow it to
     *                         include "startup" instructions loaded beginning at 0x00400000.  Specify null or
     *                         empty String to indicate there is no such designated exception handler.
     * @return ArrayList containing one MIPSProgram object for each file to assemble.
     * objects for any additional files (send ArrayList to assembler)
     * @throws ProcessingException Will throw exception if any errors occur while reading or tokenizing.
     **/
    public ArrayList<MIPSProgram> prepareFilesForAssembly(ArrayList<String> filenames, String leadFilename, String exceptionHandler) throws ProcessingException {
        ArrayList<MIPSProgram> MIPSProgramsToAssemble = new ArrayList<>();
        int leadFilePosition = 0;
        if (exceptionHandler != null && !exceptionHandler.isEmpty()) {
            filenames.addFirst(exceptionHandler);
            leadFilePosition = 1;
        }
        for (String s : filenames) {
            MIPSProgram currentProgram = (s.equals(leadFilename)) ? this : new MIPSProgram();
            currentProgram.readSource(s);
            currentProgram.tokenize();
            // I want "this" MIPSProgram to be the first in the list...except for exception handler
            if (currentProgram == this && !MIPSProgramsToAssemble.isEmpty()) {
                MIPSProgramsToAssemble.add(leadFilePosition, currentProgram);
            } else {
                MIPSProgramsToAssemble.add(currentProgram);
            }
        }
        return MIPSProgramsToAssemble;
    }

    /**
     * Assembles the MIPS source program. All files comprising the program must have
     * already been tokenized.  Assembler warnings are not considered errors.
     *
     * @param MIPSProgramsToAssemble   ArrayList of MIPSProgram objects, each representing a tokenized source file.
     * @param extendedAssemblerEnabled A boolean value - true means extended (pseudo) instructions
     *                                 are permitted in source code and false means they are to be flagged as errors.
     * @return ErrorList containing nothing or only warnings (otherwise would have thrown exception).
     * @throws ProcessingException Will throw exception if errors occur while assembling.
     **/
    public ErrorList assemble(ArrayList<MIPSProgram> MIPSProgramsToAssemble, boolean extendedAssemblerEnabled)
            throws ProcessingException {
        return assemble(MIPSProgramsToAssemble, extendedAssemblerEnabled, false);
    }

    /**
     * Assembles the MIPS source program. All files comprising the program must have
     * already been tokenized.
     *
     * @param MIPSProgramsToAssemble   ArrayList of MIPSProgram objects, each representing a tokenized source file.
     * @param extendedAssemblerEnabled True means extended (pseudo) instructions
     *                                 are permitted in source code and false means they are to be flagged as errors
     * @param warningsAreErrors        True means assembler warnings will be considered errors and terminate
     *                                 the assembly process.
     *                                 False means the assembler will produce a warning message but otherwise ignore
     *                                 warnings.
     * @return ErrorList containing nothing or only warnings (otherwise would have thrown exception).
     * @throws ProcessingException Will throw exception if errors occur while assembling.
     **/
    public ErrorList assemble(ArrayList<MIPSProgram> MIPSProgramsToAssemble, boolean extendedAssemblerEnabled,
                              boolean warningsAreErrors) throws ProcessingException {
        this.backStepper = null;
        Assembler asm = new Assembler();
        this.machineList = asm.assemble(MIPSProgramsToAssemble, extendedAssemblerEnabled, warningsAreErrors);
        this.backStepper = new BackStepper();
        return asm.getErrorList();
    }

    /**
     * Simulates execution of the MIPS program.
     * The program must have already been assembled.
     * Starts the simulation at the beginning of the text segment and continues to completion.
     *
     * @param breakPoints int array of breakpoints (PC addresses).  Can be null.
     * @return true if execution completed and false otherwise
     * @throws ProcessingException Will throw exception if errors occur while simulating.
     **/
    public boolean simulate(int[] breakPoints) throws ProcessingException {
        return this.simulateFromPC(breakPoints, -1, null);
    }

    /**
     * Simulates execution of the MIPS program.
     * The program must have already been assembled.
     * Begins simulation at the beginning of the text segment and continues to completion, or
     * until the specified maximum number of execution steps is completed.
     *
     * @param maxSteps maximum number of steps to simulate.
     * @return true if execution completed and false otherwise
     * @throws ProcessingException Will throw exception if errors occur while simulating.
     **/
    public boolean simulate(int maxSteps) throws ProcessingException {
        return this.simulateFromPC(null, maxSteps, null);
    }

    /**
     * Simulates execution of the MIPS program.
     * The program must have already been assembled.
     * Begins simulation at current program counter (PC) address and continues until stopped,
     * paused, the maximum number of steps is exceeded, or an exception is thrown.
     *
     * @param breakPoints int array of breakpoints (PC addresses).  Can be null.
     * @param maxSteps    maximum number of instruction executions.  Default -1 means no maximum.
     * @param a           the GUI component responsible for this call (GO normally).  set to null if none.
     * @return true if execution completed and false otherwise
     * @throws ProcessingException Will throw an exception if errors occur while simulating.
     **/
    public boolean simulateFromPC(int[] breakPoints, int maxSteps, AbstractAction a) throws ProcessingException {
        steppedExecution = false;
        Simulator sim = Simulator.getInstance();
        return sim.simulate(this, RegisterFile.getProgramCounter(), maxSteps, breakPoints, a);
    }

    /**
     * Simulates execution of the MIPS program.
     * The program must have already been assembled.
     * Begins simulation at current program counter (PC) address and executes one step.
     *
     * @param a the GUI component responsible for this call (STEP normally). Set to null if none.
     * @return true if execution completed and false otherwise
     * @throws ProcessingException Will throw exception if errors occur while simulating.
     **/
    @SuppressWarnings("UnusedReturnValue")
    public boolean simulateStepAtPC(AbstractAction a) throws ProcessingException {
        steppedExecution = true;
        Simulator sim = Simulator.getInstance();
        return sim.simulate(this, RegisterFile.getProgramCounter(), 1, null, a);
    }

    /**
     * Will be true only while in the process of simulating a program statement
     * in step mode (e.g., returning to GUI after each step).
     * This is used to prevent spurious AccessNotices from being sent from the Memory and Register simulator
     * to observers at other times (e.g., while updating the data and register
     * displays, while assembling the program data segment, etc.).
     */
    public boolean inSteppedExecution() {
        return steppedExecution;
    }

    /**
     * Instantiates a new {@link MacroPool} and sends reference of this
     * {@link MIPSProgram} to it
     *
     * @return The created instance of MacroPool
     * @author M.H.Sekhavat <sekhavat17@gmail.com>
     */
    public MacroPool createMacroPool() {
        macroPool = new MacroPool(this);
        return macroPool;
    }

    /**
     * Gets local macro pool {@link MacroPool} for this program
     *
     * @return MacroPool
     * @author M.H.Sekhavat <sekhavat17@gmail.com>
     */
    public MacroPool getLocalMacroPool() {
        return macroPool;
    }

    /**
     * Sets local macro pool {@link MacroPool} for this program
     *
     * @param macroPool reference to MacroPool
     * @author M.H.Sekhavat <sekhavat17@gmail.com>
     */
    public void setLocalMacroPool(MacroPool macroPool) {
        this.macroPool = macroPool;
    }
}