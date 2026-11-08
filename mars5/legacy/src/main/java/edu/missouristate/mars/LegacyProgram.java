package edu.missouristate.mars;

import edu.missouristate.mars.api.SimulatorActionSource;
import edu.missouristate.mars.simulator.Simulator;

/** Frozen frontend adapter; all program state lives in the canonical session. */
public final class LegacyProgram extends MIPSprogram {
  public LegacyProgram() {
    super(Globals.context());
  }

  @Override
  public ErrorList assemble(
      java.util.ArrayList<MIPSprogram> sources, boolean extended, boolean warnings)
      throws ProcessingException {
    try (var lease = Globals.context().claimStoppedSession()) {
      if (sources == null || sources.isEmpty())
        throw new IllegalArgumentException("At least one source file is required");
      for (var source : sources) {
        if (!assemblyContext().sharesSession(source.assemblyContext()))
          throw new IllegalArgumentException("All source files must belong to the legacy session");
      }
      edu.missouristate.mars.util.SystemIO.resetFiles();
      return super.assemble(sources, extended, warnings);
    }
  }

  @Override
  public boolean simulateFromPC(int[] points, int maximum, SimulatorActionSource actor)
      throws ProcessingException {
    setSyscallEnvironment(Globals.syscallEnvironment());
    if (actor != null)
      return Simulator.getInstance()
          .simulate(this, Globals.context().registerFile.getPC(), maximum, points, actor);
    boolean result = super.simulateFromPC(points, maximum, null);
    Globals.exitCode = getExitCode();
    return result;
  }

  @Override
  public boolean simulateStepAtPC(SimulatorActionSource actor) throws ProcessingException {
    if (actor != null)
      return Simulator.getInstance()
          .simulate(this, Globals.context().registerFile.getPC(), 1, null, actor);
    return super.simulateStepAtPC(null);
  }
}
