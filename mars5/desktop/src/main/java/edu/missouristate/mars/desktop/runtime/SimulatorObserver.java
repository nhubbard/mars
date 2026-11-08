package edu.missouristate.mars.desktop.runtime;

import edu.missouristate.mars.api.StopEvent;

/**
 * Callback interface for simulation progress notifications. Register via {@code
 * Simulator.setObserver()}.
 *
 * <p>VenusUI registers itself to refresh register, memory, and text segment windows after each
 * step. The CLI implementation is a no-op. Callbacks are invoked on the simulator thread; any UI
 * work must be dispatched to the EDT via {@code SwingUtilities.invokeLater}.
 */
public interface SimulatorObserver {

  /**
   * Called after each instruction is executed.
   *
   * @param pc The program counter value of the instruction just executed.
   */
  void onStepComplete(int pc);

  /**
   * Called when simulation halts for any reason (normal exit, exception, pause, max-steps reached).
   */
  void onSimulationStop(StopEvent event);
}
