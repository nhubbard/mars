package edu.missouristate.mars.simulator;

import edu.missouristate.mars.venus.RunSpeedPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Object provided to Observers of the Simulator.
 * They are notified at important phases of the runtime simulator,
 * such as start and stop of simulation.
 *
 * @author Pete Sanderson
 * @version January 2009
 */

public class SimulatorNotice {
    private final int action;
    private final int maxSteps;
    private final double runSpeed;
    private final int programCounter;
    public static final int SIMULATOR_START = 0;
    public static final int SIMULATOR_STOP = 1;

    /**
     * Constructor will be called only within this package, so assume
     * address and length are in valid ranges.
     */
    public SimulatorNotice(int action, int maxSteps, double runSpeed, int programCounter) {
        this.action = action;
        this.maxSteps = maxSteps;
        this.runSpeed = runSpeed;
        this.programCounter = programCounter;
    }

    /**
     * Fetch the memory address that was accessed.
     */
    public int getAction() {
        return this.action;
    }

    /**
     * Fetch the length in bytes of the access operation (4,2,1).
     */
    public int getMaxSteps() {
        return this.maxSteps;
    }

    /**
     * Fetch the value of the access operation (the value read or written).
     */
    public double getRunSpeed() {
        return this.runSpeed;
    }

    /**
     * Fetch the value of the access operation (the value read or written).
     */
    public int getProgramCounter() {
        return this.programCounter;
    }

    /**
     * String representation indicates access type, address and length in bytes
     */
    public @NotNull String toString() {
        return ((this.getAction() == SIMULATOR_START) ? "START " : "STOP  ") +
                "Max Steps " + this.maxSteps + " " +
                "Speed " + ((this.runSpeed == RunSpeedPanel.UNLIMITED_SPEED) ? "unlimited " : this.runSpeed + " inst/sec") +
                "Prog Ctr " + this.programCounter;
    }
}