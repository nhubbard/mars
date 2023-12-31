/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 */

@file:Suppress("DEPRECATION")

package edu.missouristate.mars.mips.hardware

import java.util.*

/**
 * Abstraction to represent a register for the MIPS assembler.
 */
data class Register
/**
 * @param name The name of the register.
 * @param number The number of the register.
 * @param value The initial value of the register.
 * @param resetValue The reset value of the register; defaults to initial value.
 */
@JvmOverloads constructor(
    val name: String,
    val number: Int,
    @Volatile private var value: Int,
    private var resetValue: Int = value
): Observable() {
    /**
     * Get the value of the register.
     *
     * @param notify If `true`, then Observers are notified of the READ operation. Defaults to `true`.
     */
    @Synchronized
    @JvmOverloads
    fun getValue(notify: Boolean = true): Int {
        if (notify) notifyAnyObservers(AccessNotice.AccessType.READ)
        return value
    }

    /**
     * Return the value of the Register without notifying Observers.
     */
    @Deprecated(
        "Use getValue() with optional notify parameter set to false.",
        ReplaceWith("getValue(notify = false)"),
        DeprecationLevel.ERROR
    )
    fun getValueNoNotify(): Int = getValue(notify = false)

    /**
     * Get the reset value of the Register.
     */
    fun getResetValue(): Int = resetValue

    /**
     * Set the value of the register to the value passed to it.
     * Observers are notified of the WRITE operation.
     *
     * @param newValue The new value to set the register to.
     * @return The previous value of the register.
     */
    @Synchronized
    fun setValue(newValue: Int): Int {
        val oldValue = value
        value = newValue
        notifyAnyObservers(AccessNotice.AccessType.WRITE)
        return oldValue
    }

    /**
     * Reset the value of the register to the value it was created with,
     * or a custom set reset value from the constructor.
     * Observers are not notified.
     */
    @Synchronized
    fun resetValue() {
        value = resetValue
    }

    /**
     * Change the register's reset value; the value to which it will be set when [resetValue] is called.
     */
    @Synchronized
    fun setResetValue(reset: Int) {
        resetValue = reset
    }

    /**
     * Notify any observers of a register operation that has just occurred.
     */
    private fun notifyAnyObservers(type: AccessNotice.AccessType) {
        if (countObservers() > 0) {
            setChanged()
            notifyObservers(RegisterAccessNotice(type, name))
        }
    }
}