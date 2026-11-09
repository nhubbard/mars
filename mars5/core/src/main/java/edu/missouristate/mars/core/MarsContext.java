package edu.missouristate.mars.core;

import static edu.missouristate.mars.mips.hardware.Coprocessor1State.CONDITION_FLAG_MASK;

import edu.missouristate.mars.MIPSprogram;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.api.AssemblySourceSnapshot;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.SessionSyscallEnvironment;
import edu.missouristate.mars.api.SimulatorState;
import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException;
import edu.missouristate.mars.mips.hardware.MemoryConfigurations;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.hardware.Register;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import edu.missouristate.mars.mips.instructions.SyscallRegistry;
import edu.missouristate.mars.mips.instructions.syscalls.RandomStreams;
import edu.missouristate.mars.simulator.BackStepper;
import edu.missouristate.mars.simulator.DelayedBranchState;
import edu.missouristate.mars.simulator.SimulatorEngine;
import edu.missouristate.mars.util.Binary;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Holds the complete state of one MIPS simulation session. Owns simulation state so that two
 * independent simulations can run in the same JVM without interfering (needed for tests and
 * parallel plugin simulations).
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
 * <p>All hardware, symbols, assembly and execution services belong to this component. The frontend
 * adapters are packaged separately and are not required to use this library.
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

  public final SyscallRegistry syscalls = new SyscallRegistry();

  private final AtomicInteger interrupt = new AtomicInteger();

  /** Requests an interrupt to be handled at the next instruction boundary. */
  public void requestExternalInterrupt(int cause) {
    interrupt.set(cause);
  }

  int takeExternalInterrupt() {
    return interrupt.getAndSet(0);
  }

  private volatile MIPSprogram program;
  private final ReentrantLock executionLock = new ReentrantLock();
  private volatile SimulatorEngine simulator;
  private volatile boolean closing;

  public boolean isClosing() {
    return closing;
  }

  /** The asynchronous controller for this session, created only when needed. */
  public synchronized SimulatorEngine simulator() {
    if (closing) {
      throw new IllegalStateException("Session is closed");
    }
    if (simulator == null) {
      simulator = new SimulatorEngine(this);
    }
    return simulator;
  }

  /** Serializes execution and assembly; overlapping operations fail rather than corrupt state. */
  public ExecutionLease claimExecution() {
    if (simulator != null && simulator.executingOnAnotherThread()) {
      throw new IllegalStateException("Simulator is running");
    }
    if (!executionLock.tryLock()) {
      throw new IllegalStateException("Session is already in use");
    }
    return new ExecutionLease();
  }

  /** Claims an operation that requires stopped execution, including frontend callbacks. */
  public ExecutionLease claimStoppedSession() {
    if (closing) {
      throw new IllegalStateException("Session is closed");
    }
    var lease = claimExecution();
    if (simulator != null && simulator.state() == SimulatorState.RUNNING) {
      lease.close();
      throw new IllegalStateException("Simulator is running");
    }
    return lease;
  }

  /** Changes a writable CPU register and establishes a new reverse-execution baseline. */
  public boolean editIntegerRegister(int number, int bits) {
    if (number < 1
        || (number > 31
            && number != IntegerRegisterFile.HI_REGISTER
            && number != IntegerRegisterFile.LO_REGISTER)) {
      throw new IllegalArgumentException("Register is not editable: " + number);
    }
    try (var lease = claimStoppedSession()) {
      if (registerFile.getValueNoNotify(number) == bits) {
        return false;
      }
      backStepper.clear();
      registerFile.updateRegister(number, bits);
      return true;
    }
  }

  /** Changes an implemented Coprocessor 0 register and establishes a new backstep baseline. */
  public boolean editCoprocessor0Register(int number, int bits) {
    var register = coprocessor0.getRegister(number);
    if (register == null) {
      throw new IllegalArgumentException("Coprocessor 0 register is not implemented: " + number);
    }
    return editRegister(register, bits);
  }

  /** Changes one Coprocessor 1 word and establishes a new backstep baseline. */
  public boolean editCoprocessor1Register(int number, int bits) {
    var registers = coprocessor1.getRegisters();
    return editRegister(registers[Objects.checkIndex(number, registers.length)], bits);
  }

  /** Changes a Coprocessor 1 double pair under a single stopped-session lease. */
  public boolean editCoprocessor1Pair(int number, long bits) throws InvalidRegisterAccessException {
    var registers = coprocessor1.getRegisters();
    if (number < 0 || number >= registers.length - 1 || number % 2 != 0) {
      throw new IllegalArgumentException("Coprocessor 1 register pair is not implemented: " + number);
    }
    try (var lease = claimStoppedSession()) {
      long current =
          Binary.twoIntsToLong(
              registers[number + 1].getValueNoNotify(), registers[number].getValueNoNotify());
      if (current == bits) {
        return false;
      }
      backStepper.clear();
      coprocessor1.setRegisterPairToLong(number, bits);
      return true;
    }
  }

  /** Changes the eight Coprocessor 1 condition flags and establishes a new backstep baseline. */
  public boolean editCoprocessor1ConditionFlags(int bits) {
    if ((bits & ~CONDITION_FLAG_MASK) != 0) {
      throw new IllegalArgumentException("Coprocessor 1 condition flags must fit in eight bits");
    }
    try (var lease = claimStoppedSession()) {
      if ((coprocessor1.getConditionFlagsNoNotify() & CONDITION_FLAG_MASK) == bits) {
        return false;
      }
      backStepper.clear();
      coprocessor1.setConditionFlagBits(bits);
      return true;
    }
  }

  private boolean editRegister(Register register, int bits) {
    try (var lease = claimStoppedSession()) {
      if (register.getValueNoNotify() == bits) {
        return false;
      }
      backStepper.clear();
      register.setValue(bits);
      return true;
    }
  }

  /** Lifecycle cleanup waits for an instruction boundary before closing default resources. */
  public void releaseDefaultResources() {
    executionLock.lock();
    try {
      syscallEnvironment.close();
    } finally {
      executionLock.unlock();
    }
  }

  public final class ExecutionLease implements AutoCloseable {
    private boolean closed;

    private ExecutionLease() {}

    @Override
    public void close() {
      if (!closed) {
        executionLock.unlock();
        closed = true;
      }
    }
  }

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
    settings.setBackstepStatus(() -> program != null && this.backStepper.enabled());
    this.symbolTable = symbolTable;
    this.syscallEnvironment = new SessionSyscallEnvironment(memory);
  }

  /**
   * Switches this session's address layout and resets its memory and integer registers. Call only
   * while execution is stopped. Other sessions are unaffected.
   */
  public void setMemoryConfiguration(String identifier) {
    try (var lease = claimStoppedSession()) {
      setMemoryConfigurationStopped(identifier);
    }
  }

  private void setMemoryConfigurationStopped(String identifier) {
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
    interrupt.set(0);
  }

  /**
   * Assembles files in the supplied order; the first source is the lead program. Call with
   * execution stopped.
   */
  public MIPSprogram assemble(List<Path> sources) throws ProcessingException {
    return assemble(
        sources, null, settings.isExtendedAssemblerEnabled(), settings.isWarningsAreErrors());
  }

  public MIPSprogram assemble(List<Path> sources, Path handler, boolean extended, boolean warnings)
      throws ProcessingException {
    try (var lease = claimStoppedSession()) {
      return assembleStopped(sources, handler, extended, warnings, null, () -> {});
    }
  }

  public MIPSprogram assembleSnapshot(
      AssemblySourceSnapshot snapshot, Path handler, boolean extended, boolean warnings)
      throws ProcessingException {
    return assembleSnapshot(snapshot, handler, extended, warnings, () -> {});
  }

  public MIPSprogram assembleSnapshot(
      AssemblySourceSnapshot snapshot, Path handler, boolean extended, boolean warnings,
      Runnable checkCancellation)
      throws ProcessingException {
    Objects.requireNonNull(checkCancellation);
    try (var lease = claimStoppedSession()) {
      return assembleStopped(snapshot.entries(), handler, extended, warnings, snapshot, checkCancellation);
    }
  }

  private MIPSprogram assembleStopped(
      List<Path> sources, Path handler, boolean extended, boolean warnings,
      AssemblySourceSnapshot snapshot, Runnable checkCancellation)
      throws ProcessingException {
    if (sources == null || sources.isEmpty()) {
      throw new IllegalArgumentException("At least one source file is required");
    }
    var root = new MIPSprogram(this, snapshot);
    var filenames = new ArrayList<String>();
    for (Path source : sources) {
      checkCancellation.run();
      filenames.add(source.toAbsolutePath().normalize().toString());
    }
    var files =
        root.prepareFilesForAssembly(
            filenames,
            filenames.getFirst(),
            handler == null ? null : handler.toAbsolutePath().toString(),
            checkCancellation);
    root.assemble(files, extended, warnings, checkCancellation);
    return root;
  }

  /** Returns the currently assembled lead program, or null after a reset or failed assembly. */
  public MIPSprogram getProgram() {
    return program;
  }

  void beginAssembly(MIPSprogram candidate) {
    if (candidate.assemblyContext().session() != this) {
      throw new IllegalArgumentException("Program belongs to another session");
    }
    syscallEnvironment.close();
    backStepper.clear();
    program = null;
    registerFile.reset();
    coprocessor0.resetRegisters();
    coprocessor1.resetRegisters();
    delayedBranch.clear();
    interrupt.set(0);
  }

  /** Releases default I/O files. Custom program environments are owned by their callers. */
  @Override
  public void close() {
    SimulatorEngine controller;
    synchronized (this) {
      if (closing) {
        return;
      }
      closing = true;
      controller = simulator;
    }
    if (controller != null) {
      controller.close();
    } else {
      releaseDefaultResources();
    }
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
