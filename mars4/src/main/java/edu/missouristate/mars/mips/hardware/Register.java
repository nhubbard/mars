/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

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
