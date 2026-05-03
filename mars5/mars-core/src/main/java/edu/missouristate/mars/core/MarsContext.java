package edu.missouristate.mars.core;

/**
 * Holds the complete state of one MIPS simulation session. Replaces the process-global {@link
 * edu.missouristate.mars.Globals} singleton so that two independent simulations can run in the same
 * JVM without interfering (needed for tests and parallel plugin simulations).
 *
 * <p><b>Phase 1 stub.</b> Fields are populated as the corresponding singleton references in {@code
 * Globals} are migrated to constructor injection (Phase 2). The constructor currently delegates to
 * the legacy singletons so callers can switch to {@code MarsContext} incrementally.
 *
 * <p>Usage pattern (Phase 2 target):
 *
 * <pre>{@code
 * MarsContext ctx = new MarsContext(new SimulationSettings());
 * Assembler asm = new Assembler(ctx);
 * SimulatorEngine sim = new SimulatorEngine(ctx, new ConsoleSyscallEnvironment());
 * }</pre>
 */
public final class MarsContext {

  /** Simulation settings (memory config, delayed branching, etc.). */
  public final SimulationSettings settings;

  /**
   * Creates a new, isolated simulation context backed by the given settings. Memory and registers
   * are initialized to their standard reset values as defined by the memory configuration.
   *
   * @param settings the simulation settings to use; must not be {@code null}
   */
  public MarsContext(SimulationSettings settings) {
    if (settings == null) throw new IllegalArgumentException("settings must not be null");
    this.settings = settings;
    // TODO(phase-2): instantiate Memory, RegisterFile, Coprocessor0, Coprocessor1,
    //                InstructionSet here instead of relying on Globals singletons.
  }

  /** Creates a context using the default simulation settings. */
  public MarsContext() {
    this(new SimulationSettings());
  }
}
