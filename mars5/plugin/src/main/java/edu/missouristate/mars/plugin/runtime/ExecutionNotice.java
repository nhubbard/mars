package edu.missouristate.mars.plugin.runtime;

/** Desktop observation window, used to attach register and memory listeners around a run. */
public record ExecutionNotice(int action, int maxSteps, double runSpeed, int programCounter) {
  public static final int SIMULATOR_START = 0;
  public static final int SIMULATOR_STOP = 1;
}
