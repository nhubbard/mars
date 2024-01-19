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

@file:Suppress("DEPRECATION")

package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.Globals
import java.util.Observer

/**
 * Represents the first coprocessor. It is only used for interrupt and exception registers.
 */
object Coprocessor0 {
    /* Coprocessor 0 register names */
    const val VADDR = 8
    const val STATUS = 12
    const val CAUSE = 13
    const val EPC = 14

    // The bit position in the STATUS register.
    // Bits 8-15 (mask for interrupt levels) are all set,
    // bit 4 (user mode) is set, bit 1 (exception level) is not set, and bit 0 (interrupt enable) is set.
    const val EXCEPTION_LEVEL = 1
    const val DEFAULT_STATUS_VALUE = 0x0000FF11

    @JvmStatic
    val registers = arrayOf(
        Register("$8 (vaddr)", 8, 0),
        Register("$12 (status)", 12, DEFAULT_STATUS_VALUE),
        Register("$13 (cause)", 13, 0),
        Register("$14 (epc)", 14, 0)
    )

    /** Display the register values for debugging. */
    @JvmStatic
    fun showRegisters() {
        for (register in registers) {
            println("Name: ${register.name}")
            println("Number: ${register.number}")
            println("Value: ${register.getValue()}")
            println()
        }
    }

    /**
     * Set the value of the given named register to the given new value.
     *
     * @param registerName The name of the register to set the value of (`$n`, where n is the register number).
     * @param newValue The desired value for the register.
     * @return The old value of the register prior to the update operation; 0 if the register is not found.
     */
    @JvmStatic
    fun updateRegister(registerName: String, newValue: Int): Int =
        registers.firstOrNull {
            "$${it.number}" == registerName || it.name == registerName
        }?.let {
            val oldValue = it.getValue()
            it.setValue(newValue)
            return oldValue
        } ?: 0

    /**
     * Set the value of the given register number to the given new value.
     *
     * @param registerNumber The register number to set the value of.
     * @param newValue The desired value for the register.
     * @return The old value of the register prior to the update operation; 0 if the register is not found.
     */
    @JvmStatic
    fun updateRegister(registerNumber: Int, newValue: Int): Int {
        val isBackStepperEnabled = Globals.settings.getBackSteppingEnabled()
        val backStepper = Globals.program.getBackStepper()
        return registers.firstOrNull { it.number == registerNumber }?.let {
            val oldValue =
                if (isBackStepperEnabled)
                    backStepper!!.addCoprocessor0Restore(registerNumber, it.setValue(newValue))
               else it.setValue(newValue)
            return oldValue
        } ?: 0
    }

    /**
     * Return the value of the register numbered [registerNumber].
     *
     * @param registerNumber The register number.
     * @return The value of the given register; 0 for non-implemented registers.
     */
    @JvmStatic
    fun getValue(registerNumber: Int): Int = registers.firstOrNull {
        it.number == registerNumber
    }?.getValue() ?: 0

    /**
     * Return the value of the register named [registerName].
     *
     * @param registerName The String-formatted register name to look for.
     * @return The number of the register represented by the string; -1 if no matching register was found.
     */
    @JvmStatic
    fun getNumber(registerName: String): Int =
        registers.firstOrNull {
            "$${it.number}" == registerName || it.name == registerName
        }?.number ?: -1

    /**
     * Coprocessor0 only implements some registers, so the register number (8, 12, 13, or 14) does not correspond to its
     * position in the list of registers (0, 1, 2, or 3).
     *
     * @param register A Coprocessor0 register.
     * @return The list position of the register; -1 if not found.
     */
    @JvmStatic
    fun getRegisterPosition(register: Register) = registers.indexOf(register)

    /**
     * Get the Register corresponding to the given register name. If no match, returns null.
     *
     * @param registerName The register name, in $0 format.
     * @return The Register object, or null if not found.
     */
    @JvmStatic
    fun getRegister(registerName: String): Register? =
        registers.firstOrNull { "$${it.number}" == registerName || it.name == registerName }

    /**
     * Reset the values of the Coprocessor0 registers.
     */
    @JvmStatic
    fun resetRegisters() = registers.forEach { it.resetValue() }

    /**
     * Each register is a separate object and Observable.
     * This method adds the given Observer to each register.
     *
     * @param observer The new Observer to add to all Coprocessor0 registers.
     */
    @JvmStatic
    fun addObserver(observer: Observer) = registers.forEach { it.addObserver(observer) }

    @Deprecated(
        "Renamed to addObserver.",
        ReplaceWith("addObserver(observer)"),
        DeprecationLevel.ERROR
    )
    @JvmStatic
    fun addRegisterObserver(observer: Observer) = addObserver(observer)

    /**
     * Each register is a separate object and Observable.
     * This method deletes the given Observer from each register.
     */
    @JvmStatic
    fun deleteObserver(observer: Observer) = registers.forEach { it.deleteObserver(observer) }

    @Deprecated(
        "Renamed to deleteObserver.",
        ReplaceWith("removeObserver(observer)"),
        DeprecationLevel.ERROR
    )
    @JvmStatic
    fun deleteRegisterObserver(observer: Observer) = deleteObserver(observer)
}