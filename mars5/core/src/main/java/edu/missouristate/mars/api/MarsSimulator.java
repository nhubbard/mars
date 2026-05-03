package edu.missouristate.mars.api;

/**
 * Contract for controlling execution of an assembled MIPS program. All methods return immediately
 * (they are asynchronous); callers subscribe to events via {@link SimulatorListener}.
 */
public interface MarsSimulator {

  /** Starts or resumes execution until a normal exit, exception, or step limit. */
  void run();

  /** Executes exactly one instruction and then pauses. */
  void step();

  /**
   * Pauses execution at the next instruction boundary. Has no effect if not currently running.
   */
  void pause();

  /** Stops execution unconditionally and resets the simulator to idle. */
  void stop();

  /**
   * Reverses the most recently executed instruction, restoring all state changes it made. Has no
   * effect if no back-step history is available.
   */
  void backstep();

  /** Registers a listener to receive execution events. */
  void addListener(SimulatorListener listener);

  /** Removes a previously registered listener. */
  void removeListener(SimulatorListener listener);

  /** Returns the current execution state. */
  SimulatorState state();
}
