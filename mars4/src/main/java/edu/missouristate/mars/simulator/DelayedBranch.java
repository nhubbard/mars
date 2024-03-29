package edu.missouristate.mars.simulator;

/**
 * Represents a (potential) delayed branch.  Note it is necessary only when
 * delayed branching is enabled.  Here's the protocol for using it:
 * <p>
 * (1) When a runtime decision to branch is made (by either a branch or jump
 * instruction's simulate() method in InstructionSet), then if delayed branching
 * is enabled, the register() method is called with the branch target address but
 * the program counter is NOT set to the branch target address.
 * <p>
 * (2) At the end of that instruction cycle, the simulate() method in Simulator
 * will detect the registered branch, and set its trigger.  Don't do anything yet
 * because the next instruction cycle is the delay slot and needs to complete.
 * <p>
 * (3) At the end of the next (delay slot) instruction cycle, the simulate()
 * method in Simulator will detect the triggered branch, set the program
 * counter to its target value and clear the delayed branch.
 * <p>
 * The only interesting situation is when the delay slot itself contains a
 * successful branch!  I tried this with SPIM (e.g. beq followed by b)
 * and it treats it as if nothing was there and continues the delay slot
 * into the next cycle.  The eventual branch taken is the original one (as one
 * would hope) but in the meantime the first statement following the sequence
 * of successful branches will constitute the delay slot and will be executed!
 * <p>
 * Since only one pending delayed branch can be taken at a time, everything
 * here is done with statics.  The class itself represents the potential branch.
 *
 * @author Pete Sanderson
 * @version June 2007
 **/

public class DelayedBranch {
    // Class states.
    private static final int CLEARED = 0;
    private static final int REGISTERED = 1;
    private static final int TRIGGERED = 2;

    // Initially nothing is happening.

    private static int state = CLEARED;
    private static int branchTargetAddress = 0;

    /**
     * Register the fact that a successful branch is to occur.  This is called in
     * the instruction's simulated execution (its simulate() method in InstructionSet).
     * If a branch is registered but not triggered, this registration will be ignored
     * (cannot happen if class usage protocol is followed).  If a branch is currently
     * registered and triggered, reset the state back to registered (but not triggered)
     * to carry over the delay slot for another execution cycle.  This is the
     * only public member of the class.
     *
     * @param targetAddress The address to branch to after executing the next instruction
     */
    public static void register(int targetAddress) {
        // About as clean as a switch statement can be!
        switch (state) {
            case CLEARED:
                branchTargetAddress = targetAddress;
            case REGISTERED:
            case TRIGGERED:
                state = REGISTERED;
        }
    }

    /**
     * Trigger a registered branch.  This is called at the end of the MIPS simulator
     * instruction execution cycle (simulate method in Simulator), so a registered
     * branch will be triggered right away.  The next execution cycle will be the
     * delay slot and at the end of THAT cycle, the trigger will be detected and the
     * branch carried out.  This method has package visibility.
     * <p>
     * Precondition: DelayedBranch.isRegistered()
     * <p>
     * Postcondition: DelayedBranch.isTriggered() && !DelayedBranch.isRegistered()
     */
    static void trigger() {
        // About as clean as a switch statement can be!
        switch (state) {
            case REGISTERED:
            case TRIGGERED:
                state = TRIGGERED;
            case CLEARED:
        }
    }

    /**
     * Clear the delayed branch. This must be done immediately after setting the
     * program counter to the target address.  This method has package visibility.
     */
    static void clear() {
        state = CLEARED;
        branchTargetAddress = 0;
    }

    /**
     * Return registration status.  Is false initially, true after register() is called
     * but becomes false after trigger() or clear() are called.  This method has package
     * visibility.
     *
     * @return true if branch is registered but not triggered, false otherwise.
     */

    static boolean isRegistered() {
        return state == REGISTERED;
    }

    /**
     * Return trigger status.  Is false initially, true after trigger() is called
     * but becomes false after clear() is called.  This method has package visibility.
     *
     * @return true if branch is registered but not triggered, false otherwise.
     */

    static boolean isTriggered() {
        return state == TRIGGERED;
    }


    /**
     * Return branch target address.  This should be retrieved only to set the program
     * counter at the end of the delay slot.  This method has package visibility.
     * <p>
     * Precondition: DelayedBranch.isTriggered()
     *
     * @return Target address of the delayed branch.
     */
    static int getBranchTargetAddress() {
        return branchTargetAddress;
    }

}  // DelayedBranch