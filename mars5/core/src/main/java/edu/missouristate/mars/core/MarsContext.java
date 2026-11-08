package edu.missouristate.mars.core;

import edu.missouristate.mars.MIPSprogram;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.SessionSyscallEnvironment;
import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.hardware.MemoryConfigurations;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import edu.missouristate.mars.mips.instructions.syscalls.RandomStreams;
import edu.missouristate.mars.simulator.BackStepper;
import edu.missouristate.mars.simulator.DelayedBranchState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Holds the complete state of one MIPS simulation session. Replaces the process-global {@code
 * Globals} singleton so that two independent simulations can run in the same JVM without
 * interfering (needed for tests and parallel plugin simulations).
 *
 * <p>Obtain an instance via {@link edu.missouristate.mars.di.MarsComponent}:
 *
 * <pre>{@code
 * MarsComponent component = DaggerMarsComponent.builder()
 *     .guiProxy(new HeadlessGuiProxy())
 *     .build();
 * MarsContext ctx = component.context();
 * }</pre>
 *
 * <p><b>Migration note.</b> As core classes are moved from the legacy {@code Globals} statics to
 * this context, add them as constructor parameters and expose them via final fields. Legacy GUI
 * simulator control will be migrated separately.
 */
@Singleton
public final class MarsContext implements AutoCloseable {

  /** Simulation settings (memory config, delayed branching, etc.). */
  public final SimulationSettings settings;

  /** GUI/I/O bridge — routes syscall output and dialogs to the active frontend. */
  public final MarsGuiProxy gui;

  /** Integer register state owned exclusively by this simulation. */
  public final IntegerRegisterFile registerFile;

  /** Interrupt and exception register state owned exclusively by this simulation. */
  public final Coprocessor0State coprocessor0;

  /** Floating-point register and condition-flag state owned by this simulation. */
  public final Coprocessor1State coprocessor1;

  /** Pending branch and delay-slot state owned by this simulation. */
  public final DelayedBranchState delayedBranch;

  /** Memory contents, heap, observers, and address layout owned by this simulation. */
  public final MemoryState memory;

  /** Instruction definitions used for this simulation's binary decoding. */
  public final InstructionSet instructionSet;

  /** Undo history bound exclusively to this session's hardware. */
  public final BackStepper backStepper;

  /** Global labels for the current assembly in this session. */
  public final SymbolTable symbolTable;

  /**
   * Default console and file services; caller-supplied program environments remain caller-owned.
   */
  public final SessionSyscallEnvironment syscallEnvironment;

  /** Random streams shared by instructions of this session, independent of other sessions. */
  public final RandomStreams randomStreams = new RandomStreams();

  private MIPSprogram program;

  @Inject
  public MarsContext(
      SimulationSettings settings,
      MarsGuiProxy gui,
      IntegerRegisterFile registerFile,
      Coprocessor0State coprocessor0,
      Coprocessor1State coprocessor1,
      DelayedBranchState delayedBranch,
      MemoryState memory,
      InstructionSet instructionSet,
      BackStepper backStepper,
      SymbolTable symbolTable) {
    this.settings = settings;
    this.gui = gui;
    this.registerFile = registerFile;
    this.coprocessor0 = coprocessor0;
    this.coprocessor1 = coprocessor1;
    this.delayedBranch = delayedBranch;
    this.memory = memory;
    this.instructionSet = instructionSet;
    this.backStepper = backStepper;
    this.symbolTable = symbolTable;
    this.syscallEnvironment = new SessionSyscallEnvironment(memory);
  }

  /**
   * Switches this session's address layout and resets its memory and integer registers. Call only
   * while execution is stopped. Other sessions and legacy memory are unaffected.
   */
  public void setMemoryConfiguration(String identifier) {
    var configuration = MemoryConfigurations.createConfiguration(identifier);
    syscallEnvironment.close();
    backStepper.clear();
    program = null;
    symbolTable.clear();
    memory.setConfiguration(configuration);
    memory.clear();
    settings.setMemoryConfiguration(configuration.configurationIdentifier());
    registerFile.getUserRegister("$gp").changeResetValue(configuration.getGlobalPointer());
    registerFile.getUserRegister("$sp").changeResetValue(configuration.getStackPointer());
    registerFile.getProgramCounterRegister().changeResetValue(configuration.getTextBaseAddress());
    registerFile.reset();
    delayedBranch.clear();
  }

  /**
   * Assembles files in the supplied order; the first source is the lead program. Call with
   * execution stopped.
   */
  public MIPSprogram assemble(List<Path> sources) throws ProcessingException {
    if (sources == null || sources.isEmpty())
      throw new IllegalArgumentException("At least one source file is required");
    var root = new MIPSprogram(this);
    var filenames = new ArrayList<String>();
    for (Path source : sources) filenames.add(source.toAbsolutePath().normalize().toString());
    var files = root.prepareFilesForAssembly(filenames, filenames.getFirst(), null);
    root.assemble(files, settings.isExtendedAssemblerEnabled(), settings.isWarningsAreErrors());
    return root;
  }

  /** Returns the currently assembled lead program, or null after a reset or failed assembly. */
  public MIPSprogram getProgram() {
    return program;
  }

  void beginAssembly(MIPSprogram candidate) {
    if (candidate.assemblyContext().session() != this)
      throw new IllegalArgumentException("Program belongs to another session");
    syscallEnvironment.close();
    backStepper.clear();
    program = null;
    registerFile.reset();
    coprocessor0.resetRegisters();
    coprocessor1.resetRegisters();
    delayedBranch.clear();
  }

  /** Releases default I/O files. Custom program environments are owned by their callers. */
  @Override
  public void close() {
    syscallEnvironment.close();
  }

  void finishAssembly(MIPSprogram candidate) {
    program = candidate;
    int entry =
        settings.isStartAtMainEnabled()
            ? symbolTable.getAddress(SymbolTable.getStartLabel())
            : SymbolTable.NOT_FOUND;
    registerFile.initializeProgramCounter(
        entry != SymbolTable.NOT_FOUND
                && (memory.inTextSegment(entry) || memory.inKernelTextSegment(entry))
            ? entry
            : memory.textBaseAddress);
  }
}
