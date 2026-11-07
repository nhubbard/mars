package edu.missouristate.mars.core;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
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
 * this context, add them as constructor parameters and expose them via final fields. The assembler,
 * memory, and simulator fields will be added here in later migration phases.
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

  @Inject
  public MarsContext(
      SimulationSettings settings,
      MarsGuiProxy gui,
      IntegerRegisterFile registerFile,
      Coprocessor0State coprocessor0,
      Coprocessor1State coprocessor1) {
    this.settings = settings;
    this.gui = gui;
    this.registerFile = registerFile;
    this.coprocessor0 = coprocessor0;
    this.coprocessor1 = coprocessor1;
    // TODO(phase-2+): add Memory, InstructionSet, Simulator as constructor params
    //                 once their implementations live in mars-core.
  }
}
