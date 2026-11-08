package edu.missouristate.mars.plugin.runtime;


/**
 * Identifies the caller that requested a simulator run, step, or stop, without requiring mars-core
 * to depend on Swing. VenusUI's Go/Step/Pause/Stop menu and toolbar actions implement this so
 * {@code Simulator} can distinguish "Go" from "Step" (and "Pause" from "Stop") when reporting
 * completion. A null source means the call came from a headless/CLI context.
 */
public interface SimulatorActionSource {
  /** The action's display name, e.g. "Go", "Step", "Pause", "Stop". */
  String getActionName();
}
