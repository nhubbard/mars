package edu.missouristate.mars.core;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.api.SyscallEnvironment;
import edu.missouristate.mars.mips.hardware.Coprocessor0;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.simulator.DelayedBranch;
import edu.missouristate.mars.simulator.DelayedBranchState;
import edu.missouristate.mars.util.Binary;
import java.util.Objects;

/**
 * Explicit register, branch, settings, and I/O dependencies for an instruction invocation.
 * Instructions use the supplied session rather than consulting the static register facades.
 *
 * <p>The legacy context uses the existing process-global banks and records undo operations through
 * their compatibility facades. Session contexts operate on their own banks without consulting a
 * global program's back-stepper. Memory, assembly ownership, and some syscall services still use
 * legacy global state and will be migrated separately; this is not yet a complete simulator
 * session.
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
    return session == null
        ? RegisterFile.updateRegister(number, value)
        : registerFile.updateRegister(number, value);
  }

  public int setProgramCounter(int value) {
    return session == null
        ? RegisterFile.setProgramCounter(value)
        : registerFile.setProgramCounter(value);
  }

  public int updateCoprocessor0(int number, int value) {
    return session == null
        ? Coprocessor0.updateRegister(number, value)
        : coprocessor0.updateRegister(number, value);
  }

  public int updateCoprocessor1(int number, int value) {
    return session == null
        ? Coprocessor1.updateRegister(number, value)
        : coprocessor1.updateRegister(number, value);
  }

  public int setConditionFlag(int flag) {
    return session == null
        ? Coprocessor1.setConditionFlag(flag)
        : coprocessor1.setConditionFlag(flag);
  }

  public int clearConditionFlag(int flag) {
    return session == null
        ? Coprocessor1.clearConditionFlag(flag)
        : coprocessor1.clearConditionFlag(flag);
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
