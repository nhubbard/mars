package edu.missouristate.mars.core;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.api.SyscallEnvironment;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Coprocessor0;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.simulator.BackStepper;
import edu.missouristate.mars.simulator.DelayedBranch;
import edu.missouristate.mars.simulator.DelayedBranchState;
import edu.missouristate.mars.simulator.Exceptions;
import edu.missouristate.mars.util.Binary;
import java.util.Objects;

/**
 * Explicit register, memory, branch, settings, and I/O dependencies for an instruction invocation.
 * Instructions use the supplied session rather than consulting the static register facades.
 *
 * <p>The legacy context uses the existing process-global banks and records undo operations through
 * their compatibility facades. Session contexts record their own undo history without consulting a
 * global program's back-stepper. Assembly ownership and some syscall services still use legacy
 * global state and will be migrated separately; this is not yet a complete simulator session.
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

  private InstructionContext() {
    session = null;
    environment = null;
    registerFile = RegisterFile.legacyState();
    coprocessor0 = Coprocessor0.legacyState();
    coprocessor1 = Coprocessor1.legacyState();
    delayedBranch = DelayedBranch.legacyState();
  }

  private static class LegacyHolder {
    private static final InstructionContext INSTANCE = new InstructionContext();
  }

  /** Transitional entry point for existing simulator and syscall callers. */
  public static InstructionContext legacy() {
    return LegacyHolder.INSTANCE;
  }

  public MarsSettings settings() {
    return session == null ? Globals.getSettings().getSimulationSettings() : session.settings;
  }

  /** Retains the configured legacy syscall environment only for legacy execution. */
  public SyscallEnvironment environment(SyscallEnvironment legacyEnvironment) {
    return session == null ? legacyEnvironment : environment;
  }

  public int updateRegister(int number, int value) {
    if (session == null) return RegisterFile.updateRegister(number, value);
    if (number == 32) return setProgramCounter(value);
    int old = registerFile.updateRegister(number, value);
    if (number != 0 && session.backStepper.enabled()) {
      session.backStepper.addRegisterFileRestore(number, old);
    }
    return old;
  }

  public int setProgramCounter(int value) {
    if (session == null) return RegisterFile.setProgramCounter(value);
    int old = registerFile.setProgramCounter(value);
    if (session.backStepper.enabled()) session.backStepper.addPCRestore(old);
    return old;
  }

  public int updateCoprocessor0(int number, int value) {
    if (session == null) return Coprocessor0.updateRegister(number, value);
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
    if (session == null) return Coprocessor1.updateRegister(number, value);
    int old = coprocessor1.updateRegister(number, value);
    if (number >= 0 && number < 32 && session.backStepper.enabled()) {
      session.backStepper.addCoprocessor1Restore(number, old);
    }
    return old;
  }

  public int setConditionFlag(int flag) {
    if (session == null) return Coprocessor1.setConditionFlag(flag);
    int old = coprocessor1.setConditionFlag(flag);
    if (flag >= 0 && flag < coprocessor1.getConditionFlagCount() && session.backStepper.enabled()) {
      if (old == 0) session.backStepper.addConditionFlagClear(flag);
      else session.backStepper.addConditionFlagSet(flag);
    }
    return old;
  }

  public int clearConditionFlag(int flag) {
    if (session == null) return Coprocessor1.clearConditionFlag(flag);
    int old = coprocessor1.clearConditionFlag(flag);
    if (flag >= 0 && flag < coprocessor1.getConditionFlagCount() && session.backStepper.enabled()) {
      if (old == 0) session.backStepper.addConditionFlagClear(flag);
      else session.backStepper.addConditionFlagSet(flag);
    }
    return old;
  }

  /** Memory belonging to this invocation, including its address layout and heap. */
  public MemoryState memory() {
    return session == null ? Globals.memory.legacyState() : session.memory;
  }

  public int setMemoryWord(int address, int value) throws AddressErrorException {
    if (session == null) return Globals.memory.setWord(address, value);
    int old = session.memory.setWord(address, value);
    if (session.backStepper.enabled()) session.backStepper.addMemoryRestoreWord(address, old);
    return old;
  }

  public int setMemoryHalf(int address, int value) throws AddressErrorException {
    if (session == null) return Globals.memory.setHalf(address, value);
    int old = session.memory.setHalf(address, value);
    if (session.backStepper.enabled()) session.backStepper.addMemoryRestoreHalf(address, old);
    return old;
  }

  public int setMemoryByte(int address, int value) throws AddressErrorException {
    if (session == null) return Globals.memory.setByte(address, value);
    int old = session.memory.setByte(address, value);
    if (session.backStepper.enabled()) session.backStepper.addMemoryRestoreByte(address, old);
    return old;
  }

  public int allocateBytesFromHeap(int bytes) {
    if (session == null) return Globals.memory.allocateBytesFromHeap(bytes);
    int previousHeap = session.memory.heapAddress;
    int result = session.memory.allocateBytesFromHeap(bytes);
    if (previousHeap != session.memory.heapAddress && session.backStepper.enabled()) {
      session.backStepper.addHeapRestore(previousHeap);
    }
    return result;
  }

  /** Records a raw memory write in session history, preserving legacy write behavior. */
  public int setMemoryRawWord(int address, int value) throws AddressErrorException {
    if (session == null) return Globals.memory.setRawWord(address, value);
    int old = session.memory.setRawWord(address, value);
    if (session.backStepper.enabled()) session.backStepper.addMemoryRestoreRawWord(address, old);
    return old;
  }

  /**
   * Float helper writes are undoable in sessions; legacy helpers retain their original behavior.
   */
  public void setRegisterToFloat(int number, float value) {
    if (session == null) coprocessor1.setRegisterToFloat(number, value);
    else if (number >= 0 && number < 32) updateCoprocessor1(number, Float.floatToRawIntBits(value));
  }

  public void setRegisterToFloat(String name, float value) {
    setRegisterToFloat(coprocessor1.getRegisterNumber(name), value);
  }

  public void setRegisterPairToDouble(int number, double value)
      throws InvalidRegisterAccessException {
    if (session == null) {
      coprocessor1.setRegisterPairToDouble(number, value);
      return;
    }
    if (number % 2 != 0) throw new InvalidRegisterAccessException();
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
    Objects.requireNonNull(statement);
    BackStepper history =
        session == null
            ? (Globals.program == null ? null : Globals.program.getBackStepper())
            : session.backStepper;
    boolean recording =
        history != null && history.beginInstruction(registerFile.getProgramCounter(), statement);
    try {
      registerFile.incrementPC();
      if (!(statement.getInstruction() instanceof BasicInstruction instruction)) {
        throw new ProcessingException(
            this, statement, "invalid instruction", Exceptions.RESERVED_INSTRUCTION_EXCEPTION);
      }
      instruction.getSimulationCode().simulate(statement, this);
      completeInstruction();
    } finally {
      if (recording) history.endInstruction();
    }
  }

  /** Completes a branch-delay cycle using the same protocol as the legacy simulator. */
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

  /** Normal termination preserves the legacy process exit code. */
  public void exit() {
    if (session != null) exit(0);
  }

  public void exit(int code) {
    if (session == null) {
      if (!Globals.getGui().isGuiMode()) Globals.exitCode = code;
    } else {
      exitCode = session.gui.isGuiMode() ? 0 : code;
      environment.exit(exitCode);
    }
  }

  public int getExitCode() {
    return session == null ? Globals.exitCode : exitCode;
  }
}
