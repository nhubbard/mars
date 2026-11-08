package edu.missouristate.mars;

import edu.missouristate.mars.api.SimulatorActionSource;
import edu.missouristate.mars.legacy.LegacyApplication;
import edu.missouristate.mars.simulator.Simulator;
import edu.missouristate.mars.util.SystemIO;
import java.util.ArrayList;

/** Frozen frontend adapter; all program state lives in the canonical session. */
public final class LegacyProgram extends MIPSprogram {
  public LegacyProgram() {
    super(LegacyApplication.services().context());
  }

  @Override
  public ErrorList assemble(ArrayList<MIPSprogram> sources, boolean extended, boolean warnings)
      throws ProcessingException {
    try (var lease = LegacyApplication.services().context().claimStoppedSession()) {
      if (sources == null || sources.isEmpty()) {
        throw new IllegalArgumentException("At least one source file is required");
      }
      for (var source : sources) {
        if (!assemblyContext().sharesSession(source.assemblyContext())) {
          throw new IllegalArgumentException("All source files must belong to the legacy session");
        }
      }
      SystemIO.resetFiles();
      return super.assemble(sources, extended, warnings);
    }
  }

  @Override
  public boolean simulateFromPC(int[] points, int maximum, SimulatorActionSource actor)
      throws ProcessingException {
    setSyscallEnvironment(LegacyApplication.services().syscallEnvironment());
    if (actor != null) {
      return Simulator.getInstance()
          .simulate(
              this,
              LegacyApplication.services().context().registerFile.getPC(),
              maximum,
              points,
              actor);
    }
    boolean result = super.simulateFromPC(points, maximum, null);
    LegacyApplication.services().setExitCode(getExitCode());
    return result;
  }

  @Override
  public boolean simulateStepAtPC(SimulatorActionSource actor) throws ProcessingException {
    if (actor != null) {
      return Simulator.getInstance()
          .simulate(
              this, LegacyApplication.services().context().registerFile.getPC(), 1, null, actor);
    }
    return super.simulateStepAtPC(null);
  }
}
