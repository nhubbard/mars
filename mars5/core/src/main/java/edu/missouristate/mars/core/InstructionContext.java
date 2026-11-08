package edu.missouristate.mars.core;

import edu.missouristate.mars.ErrorList;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.api.SyscallEnvironment;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.mips.instructions.SyscallRegistry;
import edu.missouristate.mars.mips.instructions.syscalls.RandomStreams;
import edu.missouristate.mars.simulator.BackStepper;
import edu.missouristate.mars.simulator.DelayedBranchState;
import edu.missouristate.mars.simulator.Exceptions;
import edu.missouristate.mars.util.Binary;
import java.util.Objects;

/**
 * Explicit register, memory, branch, settings, and I/O dependencies for an instruction invocation.
 * Instructions use the supplied session rather than consulting the static register facades.
 *
 * <p>All writes and undo operations belong to the supplied session. Execution never consults
 * process-wide registers, memory, programs, or I/O.
 */
public final class InstructionContext {
  public final IntegerRegisterFile registerFile;
  public final Coprocessor0State coprocessor0;
  public final Coprocessor1State coprocessor1;
  public final DelayedBranchState delayedBranch;
  private final MarsContext session;
  private final SyscallEnvironment environment;
  private int exitCode;

  /** Uses a session's registers and a caller-supplied syscall I/O environment. */
  public InstructionContext(MarsContext session, SyscallEnvironment environment) {
    this.session = Objects.requireNonNull(session);
    this.environment = Objects.requireNonNull(environment);
    registerFile = session.registerFile;
    coprocessor0 = session.coprocessor0;
    coprocessor1 = session.coprocessor1;
    delayedBranch = session.delayedBranch;
  }

  public SyscallRegistry syscalls() {
    return session.syscalls;
  }

  public RandomStreams randomStreams() {
    return session.randomStreams;
  }

  public MarsSettings settings() {
    return session.settings;
  }

  /** Returns the I/O environment configured for this invocation. */
  public SyscallEnvironment environment() {
    return environment;
  }

  public int updateRegister(int number, int value) {
    if (number == 32) {
      return setProgramCounter(value);
    }
    int old = registerFile.updateRegister(number, value);
    if (number != 0 && session.backStepper.enabled()) {
      session.backStepper.addRegisterFileRestore(number, old);
    }
    return old;
  }

  public int setProgramCounter(int value) {
    int old = registerFile.setProgramCounter(value);
    if (session.backStepper.enabled()) {
      session.backStepper.addPCRestore(old);
    }
    return old;
  }

  public int updateCoprocessor0(int number, int value) {
    int old = coprocessor0.updateRegister(number, value);
    if (session.backStepper.enabled()
        && (number == Coprocessor0State.VADDR
            || number == Coprocessor0State.STATUS
            || number == Coprocessor0State.CAUSE
            || number == Coprocessor0State.EPC)) {
      session.backStepper.addCoprocessor0Restore(number, old);
    }
    return old;
  }

  public int updateCoprocessor1(int number, int value) {
    int old = coprocessor1.updateRegister(number, value);
    if (number >= 0 && number < 32 && session.backStepper.enabled()) {
      session.backStepper.addCoprocessor1Restore(number, old);
    }
    return old;
  }

  public int setConditionFlag(int flag) {
    int old = coprocessor1.setConditionFlag(flag);
    if (flag >= 0 && flag < coprocessor1.getConditionFlagCount() && session.backStepper.enabled()) {
      if (old == 0) {
        session.backStepper.addConditionFlagClear(flag);
      } else {
        session.backStepper.addConditionFlagSet(flag);
      }
    }
    return old;
  }

  public int clearConditionFlag(int flag) {
    int old = coprocessor1.clearConditionFlag(flag);
    if (flag >= 0 && flag < coprocessor1.getConditionFlagCount() && session.backStepper.enabled()) {
      if (old == 0) {
        session.backStepper.addConditionFlagClear(flag);
      } else {
        session.backStepper.addConditionFlagSet(flag);
      }
    }
    return old;
  }

  /** Memory belonging to this invocation, including its address layout and heap. */
  public MemoryState memory() {
    return session.memory;
  }

  public int setMemoryWord(int address, int value) throws AddressErrorException {
    int old = session.memory.setWord(address, value);
    if (session.backStepper.enabled()) {
      session.backStepper.addMemoryRestoreWord(address, old);
    }
    return old;
  }

  public int setMemoryHalf(int address, int value) throws AddressErrorException {
    int old = session.memory.setHalf(address, value);
    if (session.backStepper.enabled()) {
      session.backStepper.addMemoryRestoreHalf(address, old);
    }
    return old;
  }

  public int setMemoryByte(int address, int value) throws AddressErrorException {
    int old = session.memory.setByte(address, value);
    if (session.backStepper.enabled()) {
      session.backStepper.addMemoryRestoreByte(address, old);
    }
    return old;
  }

  public int allocateBytesFromHeap(int bytes) {
    int previousHeap = session.memory.heapAddress;
    int result = session.memory.allocateBytesFromHeap(bytes);
    if (previousHeap != session.memory.heapAddress && session.backStepper.enabled()) {
      session.backStepper.addHeapRestore(previousHeap);
    }
    return result;
  }

  /** Records a raw memory write in session history, preserving raw-word write behavior. */
  public int setMemoryRawWord(int address, int value) throws AddressErrorException {
    int old = session.memory.setRawWord(address, value);
    if (session.backStepper.enabled()) {
      session.backStepper.addMemoryRestoreRawWord(address, old);
    }
    return old;
  }

  /** Float helper writes record undo operations in this session. */
  public void setRegisterToFloat(int number, float value) {
    if (number >= 0 && number < 32) {
      updateCoprocessor1(number, Float.floatToRawIntBits(value));
    }
  }

  public void setRegisterToFloat(String name, float value) {
    setRegisterToFloat(coprocessor1.getRegisterNumber(name), value);
  }

  public void setRegisterPairToDouble(int number, double value)
      throws InvalidRegisterAccessException {
    if (number % 2 != 0) {
      throw new InvalidRegisterAccessException();
    }
    if (number < 0 || number >= coprocessor1.getRegisters().length - 1) {
      throw new ArrayIndexOutOfBoundsException(number);
    }
    long bits = Double.doubleToRawLongBits(value);
    updateCoprocessor1(number + 1, Binary.highOrderLongToInt(bits));
    updateCoprocessor1(number, Binary.lowOrderLongToInt(bits));
  }

  public void setRegisterPairToDouble(String name, double value)
      throws InvalidRegisterAccessException {
    setRegisterPairToDouble(coprocessor1.getRegisterNumber(name), value);
  }

  /**
   * Executes one instruction cycle at the current PC, including delayed branching and an undo
   * group. Memory and registers must already be initialized. External I/O is not reversed by undo.
   * A ProcessingException with a null error list signals normal program termination.
   */
  public void execute(ProgramStatement statement) throws ProcessingException {
    execute(statement, false);
  }

  /** Executes a cycle and dispatches recoverable faults to this session's exception handler. */
  public void executeWithExceptionHandler(ProgramStatement statement) throws ProcessingException {
    execute(statement, true);
  }

  public ErrorList newErrorList() {
    return new ErrorList(session.settings);
  }

  private void execute(ProgramStatement statement, boolean dispatchHandler)
      throws ProcessingException {
    Objects.requireNonNull(statement);
    BackStepper history = session.backStepper;
    boolean recording =
        history != null && history.beginInstruction(registerFile.getProgramCounter(), statement);
    try {
      registerFile.incrementPC();
      try {
        int interrupt = session.takeExternalInterrupt();
        if (interrupt != 0) {
          throw new ProcessingException(this, statement, "External interrupt", interrupt);
        }
        if (!(statement.getInstruction() instanceof BasicInstruction instruction)) {
          throw new ProcessingException(
              this, statement, "invalid instruction", Exceptions.RESERVED_INSTRUCTION_EXCEPTION);
        }
        instruction.getSimulationCode().simulate(statement, this);
      } catch (ProcessingException error) {
        if (!dispatchHandler || error.errors() == null) {
          throw error;
        }
        ProgramStatement handler = null;
        try {
          handler = memory().getStatementNoNotify(memory().exceptionHandlerAddress);
        } catch (AddressErrorException ignored) {
        }
        if (handler == null) {
          throw error;
        }
        setProgramCounter(memory().exceptionHandlerAddress);
        delayedBranch.clear();
      }
      completeInstruction();
    } finally {
      if (recording) {
        history.endInstruction();
      }
    }
  }

  /** Completes a branch-delay cycle using this session's delayed-branch state. */
  public void completeInstruction() {
    if (delayedBranch.isTriggered()) {
      setProgramCounter(delayedBranch.getBranchTargetAddress());
      delayedBranch.clear();
    } else if (delayedBranch.isRegistered()) {
      delayedBranch.trigger();
    }
  }

  /** Records a runtime exception in this context's exception registers. */
  public void setExceptionRegisters(int cause) {
    updateCoprocessor0(
        Coprocessor0State.CAUSE,
        (coprocessor0.getValue(Coprocessor0State.CAUSE) & 0xFFFFFC83) | (cause << 2));
    updateCoprocessor0(
        Coprocessor0State.EPC, registerFile.getProgramCounter() - Instruction.INSTRUCTION_LENGTH);
    updateCoprocessor0(
        Coprocessor0State.STATUS,
        Binary.setBit(
            coprocessor0.getValue(Coprocessor0State.STATUS), Coprocessor0State.EXCEPTION_LEVEL));
  }

  /** Terminates this invocation with the requested exit code. */
  public void exit() {
    exit(0);
  }

  public void exit(int code) {
    exitCode = session.gui.isGuiMode() ? 0 : code;
    environment.exit(exitCode);
  }

  public int getExitCode() {
    return exitCode;
  }
}
