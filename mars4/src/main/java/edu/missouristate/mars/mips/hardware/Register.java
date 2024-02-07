package edu.missouristate.mars.mips.hardware;

import java.util.Observable;

/**
 * Abstraction to represent a register of a MIPS Assembler.
 *
 * @author Jason Bumgarner, Jason Shrewsbury, Ben Sherman
 * @version June 2003
 **/

public class Register extends Observable {
    private final String name;
    private final int number;
    private int resetValue;
    // volatile should be enough to allow safe multi-threaded access
    // w/o the use of synchronized methods.  getValue and setValue
    // are the only methods here used by the register collection
    // (RegisterFile, Coprocessor0, Coprocessor1) methods.
    private volatile int value;

    /**
     * Creates a new register with specified name, number, and value.
     *
     * @param n   The name of the register.
     * @param num The number of the register.
     * @param val The inital (and reset) value of the register.
     */

    public Register(String n, int num, int val) {
        name = n;
        number = num;
        value = val;
        resetValue = val;
    }

    /**
     * Returns the name of the Register.
     *
     * @return name The name of the Register.
     */

    public String getName() {
        return name;
    }

    /**
     * Returns the value of the Register.  Observers are notified
     * of the READ operation.
     *
     * @return value The value of the Register.
     */

    public synchronized int getValue() {
        notifyAnyObservers(AccessNotice.READ);
        return value;
    }


    /**
     * Returns the value of the Register.  Observers are not notified.
     * Added for release 3.8.
     *
     * @return value The value of the Register.
     */

    public synchronized int getValueNoNotify() {
        return value;
    }


    /**
     * Returns the reset value of the Register.
     *
     * @return The reset (initial) value of the Register.
     */

    public int getResetValue() {
        return resetValue;
    }

    /**
     * Returns the number of the Register.
     *
     * @return number The number of the Register.
     */

    public int getNumber() {
        return number;
    }

    /**
     * Sets the value of the register to the val passed to it.
     * Observers are notified of the WRITE operation.
     *
     * @param val Value to set the Register to.
     * @return previous value of register
     */

    public synchronized int setValue(int val) {
        int old = value;
        value = val;
        notifyAnyObservers(AccessNotice.WRITE);
        return old;
    }

    /**
     * Resets the value of the register to the value it was constructed with.
     * Observers are not notified.
     */

    public synchronized void resetValue() {
        value = resetValue;
    }

    /**
     * Change the register's reset value; the value to which it will be
     * set when <tt>resetValue()</tt> is called.
     */

    public synchronized void changeResetValue(int reset) {
        resetValue = reset;
    }

    //
    // Method to notify any observers of register operation that has just occurred.
    //
    private void notifyAnyObservers(int type) {
        if (this.countObservers() > 0) {// && Globals.program != null) && Globals.program.inSteppedExecution()) {
            this.setChanged();
            this.notifyObservers(new RegisterAccessNotice(type, this.name));
        }
    }


}
