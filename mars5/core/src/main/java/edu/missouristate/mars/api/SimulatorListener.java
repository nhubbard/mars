package edu.missouristate.mars.api;

/**
 * Receives execution events from a {@link MarsSimulator}. All callbacks are invoked on the
 * simulator thread; implementations must not block.
 */
public interface SimulatorListener {

  /**
   * Called after each completed instruction, including the terminating syscall.
   *
   * @param event details of the step that just completed
   */
  void onStep(StepEvent event);

  /**
   * Called when execution stops for any reason.
   *
   * @param event details of why execution stopped
   */
  void onStop(StopEvent event);

  /**
   * Called when a run-time error occurs.
   *
   * @param event details of the error
   */
  void onError(ErrorEvent event);
}
