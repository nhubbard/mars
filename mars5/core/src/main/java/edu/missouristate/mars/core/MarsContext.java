package edu.missouristate.mars.core;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.hardware.MemoryConfigurations;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import edu.missouristate.mars.simulator.DelayedBranchState;
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
 * this context, add them as constructor parameters and expose them via final fields. The assembler
 * and simulator fields will be added here in later migration phases.
 */
@Singleton
public final class MarsContext {

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

  @Inject
  public MarsContext(
      SimulationSettings settings,
      MarsGuiProxy gui,
      IntegerRegisterFile registerFile,
      Coprocessor0State coprocessor0,
      Coprocessor1State coprocessor1,
      DelayedBranchState delayedBranch,
      MemoryState memory,
      InstructionSet instructionSet) {
    this.settings = settings;
    this.gui = gui;
    this.registerFile = registerFile;
    this.coprocessor0 = coprocessor0;
    this.coprocessor1 = coprocessor1;
    this.delayedBranch = delayedBranch;
    this.memory = memory;
    this.instructionSet = instructionSet;
  }

  /**
   * Switches this session's address layout and resets its memory and integer registers. Call only
   * while execution is stopped. Other sessions and legacy memory are unaffected.
   */
  public void setMemoryConfiguration(String identifier) {
    var configuration = MemoryConfigurations.createConfiguration(identifier);
    memory.setConfiguration(configuration);
    memory.clear();
    settings.setMemoryConfiguration(configuration.configurationIdentifier());
    registerFile.getUserRegister("$gp").changeResetValue(configuration.getGlobalPointer());
    registerFile.getUserRegister("$sp").changeResetValue(configuration.getStackPointer());
    registerFile.getProgramCounterRegister().changeResetValue(configuration.getTextBaseAddress());
    registerFile.reset();
    delayedBranch.clear();
  }
}
