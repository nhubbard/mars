package edu.missouristate.mars.core;

import edu.missouristate.mars.api.MarsGuiProxy;
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
 * this context, add them as constructor parameters and expose them via final fields. The
 * {@code MarsAssembler}, {@code MarsMemory}, {@code MarsSimulator}, and {@code MarsRegisterFile}
 * fields will be added here in later migration phases.
 */
@Singleton
public final class MarsContext {

  /** Simulation settings (memory config, delayed branching, etc.). */
  public final SimulationSettings settings;

  /** GUI/I/O bridge — routes syscall output and dialogs to the active frontend. */
  public final MarsGuiProxy gui;

  @Inject
  public MarsContext(SimulationSettings settings, MarsGuiProxy gui) {
    this.settings = settings;
    this.gui = gui;
    // TODO(phase-2+): add Memory, RegisterFile, InstructionSet, Simulator as constructor params
    //                 once their implementations live in mars-core.
  }
}
