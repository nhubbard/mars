package edu.missouristate.mars;

import edu.missouristate.mars.api.AssembledInstruction;
import edu.missouristate.mars.api.AssemblySourceReader;
import edu.missouristate.mars.api.AssemblySourceSnapshot;
import edu.missouristate.mars.api.AssemblySymbolMap;
import edu.missouristate.mars.api.InstructionSourceMap;
import edu.missouristate.mars.api.InstructionSourceMap.Location;
import edu.missouristate.mars.api.InstructionSourceMap.Origin;
import edu.missouristate.mars.api.Program;
import edu.missouristate.mars.api.StepEvent;
import edu.missouristate.mars.api.StopEvent;
import edu.missouristate.mars.api.SyscallEnvironment;
import edu.missouristate.mars.assembler.*;
import edu.missouristate.mars.core.AssemblyContext;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.simulator.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Internal representations of MIPS program. Connects source, tokens and machine code. Having all
 * these structures available facilitates construction of good messages, debugging, and easy
 * simulation.
 *
 * @author Pete Sanderson
 * @version August 2003
 */
public class MIPSprogram implements Program {
  private InstructionSourceMap sourceMap = InstructionSourceMap.EMPTY;
  private AssemblySymbolMap symbolMap = AssemblySymbolMap.EMPTY;
  private final AssemblyContext assemblyContext;
  private final AssemblySourceSnapshot sourceSnapshot;
  private SyscallEnvironment syscallEnvironment;
  private InstructionContext sessionExecution;
  private ErrorList assemblyDiagnostics;

  public ErrorList getAssemblyDiagnostics() {
    return assemblyDiagnostics;
  }

  /** Program whose sources, symbols, memory, and synchronous execution belong to one session. */
  public MIPSprogram(MarsContext session) {
    this(session, null);
  }

  /** Program that reads one immutable assembly snapshot, or uses disk sources if it is null. */
  public MIPSprogram(MarsContext session, AssemblySourceSnapshot sourceSnapshot) {
    assemblyContext = new AssemblyContext(session);
    this.sourceSnapshot = sourceSnapshot;
  }

  /** Creates the execution handle for an already assembled image. */
  public static MIPSprogram fromImage(
      MarsContext session, String filename, ArrayList<ProgramStatement> instructions,
      InstructionSourceMap sourceMap, AssemblySymbolMap symbolMap) {
    var program = new MIPSprogram(session);
    program.filename = filename;
    program.machineList = instructions;
    program.sourceMap = sourceMap;
    program.symbolMap = symbolMap;
    program.localSymbolTable = new SymbolTable(session.symbolTable);
    program.localSymbolTable.loadDefinitions(symbolMap.locals().getOrDefault(Path.of(filename), Map.of()));
    program.backStepper = session.backStepper;
    return program;
  }

  public AssemblyContext assemblyContext() {
    return assemblyContext;
  }

  public ErrorList newErrorList() {
    return assemblyContext.newErrorList();
  }

  public MIPSprogram createRelatedProgram() {
    return new MIPSprogram(assemblyContext.session(), sourceSnapshot);
  }

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
   * Produces list of source statements that comprise the program.
   *
   * @return ArrayList of String. Each String is one line of MIPS source code.
   */
  public ArrayList<String> getSourceList() {
    return sourceList;
  }

  /**
   * Set list of source statements that comprise the program.
   *
   * @param sourceLineList ArrayList of SourceLine. Each SourceLine represents one line of MIPS
   *     source code.
   */
  public void setSourceLineList(ArrayList<SourceLine> sourceLineList) {
    this.sourceLineList = sourceLineList;
    sourceList = new ArrayList<>();
    for (SourceLine sl : sourceLineList) {
      sourceList.add(sl.getSource());
    }
  }

  /**
   * Retrieve list of source statements that comprise the program.
   *
   * @return ArrayList of SourceLine. Each SourceLine represents one line of MIPS source cod
   */
  public ArrayList<SourceLine> getSourceLineList() {
    return this.sourceLineList;
  }

  /**
   * Produces name of associated source code file.
   *
   * @return File name as String.
   */
  public String getFilename() {
    return filename;
  }

  /** Identifies a source and its relative-include directory without changing its display path. */
  public Path getIncludeIdentity() {
    Path path = Path.of(filename).toAbsolutePath();
    if (sourceSnapshot != null) {
      return sourceSnapshot.includeIdentity(path);
    }
    try {
      // Keep the final name. A file symlink's relative includes still start beside that link.
      return path.getParent().toRealPath().resolve(path.getFileName());
    } catch (IOException unavailable) {
      // The source may have been removed since it was read.
      return path.normalize();
    }
  }

  /**
   * Produces list of tokens that comprise the program.
   *
   * @return ArrayList of TokenList. Each TokenList is list of tokens generated by corresponding
   *     line of MIPS source code.
   * @see TokenList
   */
  public ArrayList<TokenList> getTokenList() {
    return tokenList;
  }

  /**
   * Retrieves Tokenizer for this program
   *
   * @return Tokenizer
   */
  public Tokenizer getTokenizer() {
    return tokenizer;
  }

  /**
   * Produces new empty list to hold parsed source code statements.
   *
   * @return ArrayList of ProgramStatement. Each ProgramStatement represents a parsed MIPS
   *     statement.
   * @see ProgramStatement
   */
  public ArrayList<ProgramStatement> createParsedList() {
    parsedList = new ArrayList<>();
    return parsedList;
  }

  /**
   * Produces existing list of parsed source code statements.
   *
   * @return ArrayList of ProgramStatement. Each ProgramStatement represents a parsed MIPS
   *     statement.
   * @see ProgramStatement
   */
  public ArrayList<ProgramStatement> getParsedList() {
    return parsedList;
  }

  /**
   * Produces list of machine statements that are assembled from the program.
   *
   * @return ArrayList of ProgramStatement. Each ProgramStatement represents an assembled basic MIPS
   *     instruction.
   * @see ProgramStatement
   */
  public ArrayList<ProgramStatement> getMachineList() {
    return machineList;
  }

  /**
   * Returns BackStepper associated with this program. It is created upon successful assembly.
   *
   * @return BackStepper object, null if there is none.
   */
  public BackStepper getBackStepper() {
    if (assemblyContext.session() != null && assemblyContext.session().getProgram() != this) {
      return null;
    }
    return backStepper;
  }

  /**
   * Returns SymbolTable associated with this program. It is created at assembly time, and stores
   * local labels (those not declared using .globl directive).
   */
  public SymbolTable getLocalSymbolTable() {
    return localSymbolTable;
  }

  /**
   * Returns status of BackStepper associated with this program.
   *
   * @return true if enabled, false if disabled or non-existant.
   */
  public boolean backSteppingEnabled() {
    BackStepper current = getBackStepper();
    return current != null && current.enabled();
  }

  /**
   * Produces specified line of MIPS source program.
   *
   * @param i Line number of MIPS source program to get. Line 1 is first line.
   * @return Returns specified line of MIPS source. If outside the line range, it returns null. Line
   *     1 is first line.
   */
  public String getSourceLine(int i) {
    if ((i >= 1) && (i <= sourceList.size())) {
      return sourceList.get(i - 1);
    } else {
      return null;
    }
  }

  /**
   * Reads MIPS source code using the program's source snapshot or disk source.
   *
   * @param file String containing name of MIPS source code file.
   * @throws ProcessingException Will throw exception if there is any problem reading the file.
   */
  public void readSource(String file) throws ProcessingException {
    readSource(file, () -> {});
  }

  public void readSource(String file, Runnable checkCancellation) throws ProcessingException {
    readSource(file, checkCancellation, false);
  }

  public void readIncludedSource(String file, Runnable checkCancellation) throws ProcessingException {
    readSource(file, checkCancellation, true);
  }

  private void readSource(String file, Runnable checkCancellation, boolean include)
      throws ProcessingException {
    this.filename = file;
    this.sourceList = new ArrayList<>();
    this.sourceLineList = null;
    ErrorList errors;
    String line;
    try (BufferedReader inputFile = new BufferedReader(openSource(file, include))) {
      line = inputFile.readLine();
      while (line != null) {
        checkCancellation.run();
        sourceList.add(line);
        line = inputFile.readLine();
      }
    } catch (CancellationException cancelled) {
      throw cancelled;
    } catch (Exception e) {
      errors = newErrorList();
      errors.add(new ErrorMessage(sourceSnapshot == null ? null : this, 0, 0, e.toString()));
      throw new ProcessingException(errors);
    }
  }

  private Reader openSource(String file, boolean include) throws IOException {
    if (sourceSnapshot == null) {
      return AssemblySourceReader.open(Path.of(file));
    }
    Path requested = Path.of(file);
    Path path = include
        ? sourceSnapshot.resolveInclude(requested)
        : requested.toAbsolutePath().normalize();
    String text = path == null ? null : sourceSnapshot.texts().get(path);
    if (text == null) {
      throw new FileNotFoundException("Source was not captured: " + file);
    }
    filename = path.toString();
    return new StringReader(text);
  }

  /**
   * Tokenizes the MIPS source program. Source text must already be loaded.
   *
   * @throws ProcessingException Will throw exception if errors occured while tokenizing.
   */
  public void tokenize() throws ProcessingException {
    tokenize(() -> {});
  }

  private void tokenize(Runnable checkCancellation) throws ProcessingException {
    this.tokenizer = assemblyContext.tokenizer(this);
    this.tokenList = tokenizer.tokenize(this, checkCancellation);
    this.localSymbolTable = new SymbolTable(assemblyContext.symbols()); // prepare for assembly
  }

  /**
   * Prepares the given list of files for assembly. This involves reading and tokenizing all the
   * source files. There may be only one.
   *
   * @param filenames ArrayList containing the source file name(s) in no particular order
   * @param leadFilename String containing name of source file that needs to go first and will be
   *     represented by "this" MIPSprogram object.
   * @param exceptionHandler String containing name of source file containing exception handler.
   *     This will be assembled first, even ahead of leadFilename, to allow it to include "startup"
   *     instructions loaded beginning at 0x00400000. Specify null or empty String to indicate there
   *     is no such designated exception handler.
   * @return ArrayList containing one MIPSprogram object for each file to assemble. objects for any
   *     additional files (send ArrayList to assembler)
   * @throws ProcessingException Will throw exception if errors occured while reading or tokenizing.
   */
  public ArrayList<MIPSprogram> prepareFilesForAssembly(
      ArrayList<String> filenames, String leadFilename, String exceptionHandler)
      throws ProcessingException {
    return prepareFilesForAssembly(filenames, leadFilename, exceptionHandler, () -> {});
  }

  public ArrayList<MIPSprogram> prepareFilesForAssembly(
      ArrayList<String> filenames, String leadFilename, String exceptionHandler,
      Runnable checkCancellation)
      throws ProcessingException {
    ArrayList<MIPSprogram> MIPSprogramsToAssemble = new ArrayList<>();
    int leadFilePosition = 0;
    if (exceptionHandler != null && !exceptionHandler.isEmpty()) {
      filenames.addFirst(exceptionHandler);
      leadFilePosition = 1;
    }
    for (String filename : filenames) {
      checkCancellation.run();
      MIPSprogram preparee = (filename.equals(leadFilename)) ? this : createRelatedProgram();
      preparee.readSource(filename, checkCancellation);
      preparee.tokenize(checkCancellation);
      // I want "this" MIPSprogram to be the first in the list...except for exception handler
      if (preparee == this && !MIPSprogramsToAssemble.isEmpty()) {
        MIPSprogramsToAssemble.add(leadFilePosition, preparee);
      } else {
        MIPSprogramsToAssemble.add(preparee);
      }
    }
    return MIPSprogramsToAssemble;
  }

  /**
   * Assembles the MIPS source program. All files comprising the program must have already been
   * tokenized. Assembler warnings are not considered errors.
   *
   * @param MIPSprogramsToAssemble ArrayList of MIPSprogram objects, each representing a tokenized
   *     source file.
   * @param extendedAssemblerEnabled A boolean value - true means extended (pseudo) instructions are
   *     permitted in source code and false means they are to be flagged as errors.
   * @throws ProcessingException Will throw exception if errors occured while assembling.
   * @return ErrorList containing nothing or only warnings (otherwise would have thrown exception).
   */
  public ErrorList assemble(
      ArrayList<MIPSprogram> MIPSprogramsToAssemble, boolean extendedAssemblerEnabled)
      throws ProcessingException {
    return assemble(MIPSprogramsToAssemble, extendedAssemblerEnabled, false);
  }

  /**
   * Assembles the MIPS source program. All files comprising the program must have already been
   * tokenized.
   *
   * @param MIPSprogramsToAssemble ArrayList of MIPSprogram objects, each representing a tokenized
   *     source file.
   * @param extendedAssemblerEnabled A boolean value - true means extended (pseudo) instructions are
   *     permitted in source code and false means they are to be flagged as errors
   * @param warningsAreErrors A boolean value - true means assembler warnings will be considered
   *     errors and terminate the assemble; false means the assembler will produce warning message
   *     but otherwise ignore warnings.
   * @throws ProcessingException Will throw exception if errors occured while assembling.
   * @return ErrorList containing nothing or only warnings (otherwise would have thrown exception).
   */
  public ErrorList assemble(
      ArrayList<MIPSprogram> MIPSprogramsToAssemble,
      boolean extendedAssemblerEnabled,
      boolean warningsAreErrors)
      throws ProcessingException {
    return assemble(MIPSprogramsToAssemble, extendedAssemblerEnabled, warningsAreErrors, () -> {});
  }

  public ErrorList assemble(
      ArrayList<MIPSprogram> MIPSprogramsToAssemble,
      boolean extendedAssemblerEnabled,
      boolean warningsAreErrors,
      Runnable checkCancellation)
      throws ProcessingException {
    try (var lease =
        assemblyContext.session() == null
            ? null
            : assemblyContext.session().claimStoppedSession()) {
      return assembleStopped(
          MIPSprogramsToAssemble, extendedAssemblerEnabled, warningsAreErrors, checkCancellation);
    }
  }

  private ErrorList assembleStopped(
      ArrayList<MIPSprogram> MIPSprogramsToAssemble,
      boolean extendedAssemblerEnabled,
      boolean warningsAreErrors,
      Runnable checkCancellation)
      throws ProcessingException {
    if (assemblyContext.session() != null
        && (MIPSprogramsToAssemble == null || MIPSprogramsToAssemble.isEmpty())) {
      throw new IllegalArgumentException("At least one source file is required");
    }
    if (MIPSprogramsToAssemble != null) {
      for (MIPSprogram source : MIPSprogramsToAssemble) {
        if (!assemblyContext.sharesSession(source.assemblyContext())) {
          throw new IllegalArgumentException(
              "All source files must belong to the program's session");
        }
      }
    }
    this.backStepper = null;
    Assembler asm = new Assembler(assemblyContext);
    this.machineList =
        asm.assemble(MIPSprogramsToAssemble, extendedAssemblerEnabled, warningsAreErrors,
            checkCancellation);
    sourceMap = asm.getSourceMap();
    symbolMap = asm.getSymbolMap();
    checkCancellation.run();
    this.backStepper = assemblyContext.session().backStepper;
    sessionExecution = null;
    assemblyContext.finishAssembly(this);
    assemblyDiagnostics = asm.getErrorList();
    return assemblyDiagnostics;
  }

  /**
   * Simulates execution of the MIPS program. Program must have already been assembled. Begins
   * simulation at beginning of text segment and continues to completion.
   *
   * @param breakPoints int array of breakpoints (PC addresses). Can be null.
   * @return true if execution completed and false otherwise
   * @throws ProcessingException Will throw exception if errors occured while simulating.
   */
  public boolean simulate(int[] breakPoints) throws ProcessingException {
    return this.simulateFromPC(breakPoints, -1);
  }

  /**
   * Simulates execution of the MIPS program. Program must have already been assembled. Begins
   * simulation at beginning of text segment and continues to completion or until the specified
   * maximum number of steps are simulated.
   *
   * @param maxSteps maximum number of steps to simulate.
   * @return true if execution completed and false otherwise
   * @throws ProcessingException Will throw exception if errors occured while simulating.
   */
  public boolean simulate(int maxSteps) throws ProcessingException {
    return this.simulateFromPC(null, maxSteps);
  }

  /**
   * Simulates from the current PC, reporting the number of instructions completed in this call.
   * The observer runs on the caller's thread after each completed instruction, including a normal
   * exit syscall. An empty instruction slot or a failing instruction does not increment the count.
   */
  public boolean simulate(int maxSteps, LongConsumer instructionCount) throws ProcessingException {
    return simulateSession(null, maxSteps, Objects.requireNonNull(instructionCount));
  }

  /**
   * Simulates execution of the MIPS program. Program must have already been assembled. Begins
   * simulation at current program counter address and continues until stopped, paused, maximum
   * steps exceeded, or exception occurs.
   *
   * @param breakPoints int array of breakpoints (PC addresses). Can be null.
   * @param maxSteps maximum number of instruction executions. Default -1 means no maximum.
   * @return true if execution completed and false otherwise
   * @throws ProcessingException Will throw exception if errors occured while simulating.
   */
  public boolean simulateFromPC(int[] breakPoints, int maxSteps) throws ProcessingException {
    return simulateSession(breakPoints, maxSteps, null);
  }

  /**
   * Simulates execution of the MIPS program. Program must have already been assembled. Begins
   * simulation at current program counter address and executes one step.
   *
   * @return true if execution completed and false otherwise
   * @throws ProcessingException Will throw exception if errors occured while simulating.
   */
  public boolean simulateStepAtPC() throws ProcessingException {
    return simulateSession(null, 1, null);
  }

  /** Sets I/O for a session program; call while execution is stopped. */
  public void setSyscallEnvironment(SyscallEnvironment environment) {
    Objects.requireNonNull(environment);
    try (var lease = assemblyContext.session().claimStoppedSession()) {
      syscallEnvironment = environment;
      sessionExecution = null;
    }
  }

  public int getExitCode() {
    return sessionExecution == null ? 0 : sessionExecution.getExitCode();
  }

  @Override
  public int instructionCount() {
    return machineList == null ? 0 : machineList.size();
  }

  @Override
  public InstructionSourceMap sourceMap() {
    return sourceMap;
  }

  @Override
  public List<AssembledInstruction> instructionsAt(Location location, Runnable checkCancellation) {
    if (machineList == null) {
      return List.of();
    }
    var instructions = new ArrayList<AssembledInstruction>();
    for (var statement : machineList) {
      checkCancellation.run();
      int address = statement.getAddress();
      var origin = sourceMap.origins().get(address);
      for (var frame = origin; frame != null; frame = frame.caller()) {
        if (frame.location().equals(location)) {
          instructions.add(assembledInstruction(statement, origin));
          break;
        }
      }
    }
    return List.copyOf(instructions);
  }

  @Override
  public List<AssembledInstruction> pseudoInstructions(Runnable checkCancellation) {
    var addresses = sourceMap.pseudoAddresses();
    if (addresses.isEmpty()) {
      return List.of();
    }
    var instructions = new ArrayList<AssembledInstruction>(addresses.size());
    for (var statement : machineList) {
      checkCancellation.run();
      int address = statement.getAddress();
      if (addresses.contains(address)) {
        instructions.add(assembledInstruction(statement, sourceMap.origins().get(address)));
      }
    }
    return List.copyOf(instructions);
  }

  private static AssembledInstruction assembledInstruction(ProgramStatement statement, Origin origin) {
    return new AssembledInstruction(statement.getAddress(), statement.getBinaryStatement(),
        statement.getPrintableBasicAssemblyStatement(), origin);
  }

  @Override
  public AssemblySymbolMap symbolMap() {
    return symbolMap;
  }

  /** Result of one session instruction; a null stop reason means execution can continue. */
  public record ExecutionStep(
      int pc,
      int instruction,
      boolean executed,
      StopEvent.Reason stopReason,
      StepEvent.Transfer transfer,
      StepEvent.BranchOutcome branchOutcome) {
    public ExecutionStep(int pc, int instruction, boolean executed, StopEvent.Reason stopReason) {
      this(pc, instruction, executed, stopReason, null);
    }

    public ExecutionStep(
        int pc, int instruction, boolean executed, StopEvent.Reason stopReason,
        StepEvent.Transfer transfer) {
      this(pc, instruction, executed, stopReason, transfer, null);
    }
  }

  /** Executes one instruction on the owning session's execution thread. */
  public ExecutionStep executeSessionInstruction() throws ProcessingException {
    try (var lease = assemblyContext.session().claimExecution()) {
      return edu.missouristate.mars.mips.hardware.AccessOrigin.duringExecution(
          this::executeSessionInstructionStopped);
    }
  }

  private ExecutionStep executeSessionInstructionStopped() throws ProcessingException {
    MarsContext session = assemblyContext.session();
    if (session == null || session.getProgram() != this || machineList == null) {
      throw new IllegalStateException("This is not the current assembled program of its session");
    }
    if (sessionExecution == null) {
      sessionExecution =
          new InstructionContext(
              session,
              syscallEnvironment == null ? session.syscallEnvironment : syscallEnvironment);
    }
    int pc = session.registerFile.getProgramCounter();
    ProgramStatement statement;
    try {
      statement = session.memory.fetchInstruction(pc);
    } catch (AddressErrorException error) {
      var location = new ProgramStatement(0, pc, session.instructionSet);
      boolean recording = session.backStepper.beginInstruction(pc, location);
      try {
        session.registerFile.incrementPC();
        throw new ProcessingException(sessionExecution, location, error);
      } finally {
        if (recording) {
          session.backStepper.endInstruction();
        }
      }
    }
    if (statement == null) {
      session.delayedBranch.clear();
      return new ExecutionStep(pc, 0, false, StopEvent.Reason.CLIFF_TERMINATION);
    }
    try {
      sessionExecution.executeWithExceptionHandler(statement);
    } catch (ProcessingException error) {
      session.delayedBranch.clear();
      if (error.errors() != null) {
        throw error;
      }
      return new ExecutionStep(
          pc, statement.getBinaryStatement(), true, StopEvent.Reason.NORMAL_EXIT);
    }
    return new ExecutionStep(
        pc, statement.getBinaryStatement(), true, null, sessionExecution.lastTransfer(),
        sessionExecution.lastBranchOutcome());
  }

  /** Synchronous, frontend-independent execution for a session-owned program. */
  private boolean simulateSession(int[] breakPoints, int maxSteps, LongConsumer instructionCount)
      throws ProcessingException {
    MarsContext session = assemblyContext.session();
    try (var lease = session.claimStoppedSession()) {
      long steps = 0;
      while (maxSteps <= 0 || steps < maxSteps) {
        if (Thread.currentThread().isInterrupted() || session.isClosing()) {
          return false;
        }
        var result = executeSessionInstruction();
        if (result.executed()) {
          steps++;
          if (instructionCount != null) {
            instructionCount.accept(steps);
          }
        }
        if (result.stopReason() != null) {
          return true;
        }
        if (breakPoints != null) {
          for (int breakpoint : breakPoints) {
            if (breakpoint == session.registerFile.getProgramCounter()) {
              return false;
            }
          }
        }
      }
      return false;
    }
  }

  /**
   * Instantiates a new {@link MacroPool} and sends reference of this {@link MIPSprogram} to it
   *
   * @return instatiated MacroPool
   * @author M.H.Sekhavat {@literal <sekhavat17@gmail.com>}
   */
  public MacroPool createMacroPool() {
    macroPool = new MacroPool(this);
    return macroPool;
  }

  /**
   * Gets local macro pool {@link MacroPool} for this program
   *
   * @return MacroPool
   * @author M.H.Sekhavat {@literal <sekhavat17@gmail.com>}
   */
  public MacroPool getLocalMacroPool() {
    return macroPool;
  }

  /**
   * Sets local macro pool {@link MacroPool} for this program
   *
   * @param macroPool reference to MacroPool
   * @author M.H.Sekhavat {@literal <sekhavat17@gmail.com>}
   */
  public void setLocalMacroPool(MacroPool macroPool) {
    this.macroPool = macroPool;
  }
} // MIPSprogram
