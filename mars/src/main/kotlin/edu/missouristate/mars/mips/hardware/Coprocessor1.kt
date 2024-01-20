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

@file:Suppress("NAME_SHADOWING", "DEPRECATION", "MemberVisibilityCanBePrivate")

package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.*
import edu.missouristate.mars.util.Binary
import java.util.*

/**
 * Represents MIPS coprocessor 1, the floating point unit.
 */
object Coprocessor1 {
    /*
     * Adapted from RegisterFile class developed by Bumgarner et al. in 2003.
     * The FPU registers will be implemented by Register objects.  Such objects
     * can only hold int values, but we can use Float.floatToIntBits() to translate
     * a 32 bit float value into its equivalent 32-bit int representation, and
     * Float.intBitsToFloat() to bring it back.  More importantly, there are
     * similar methods Double.doubleToLongBits() and Double.LongBitsToDouble()
     * which can be used to extend a double value over 2 registers.  The resulting
     * long is split into 2 int values (high-order 32 bits, low-order 32 bits) for
     * storing into registers, and reassembled upon retrieval.
     */

    @JvmStatic val registers = arrayOf(
        *(0..<32).map { Register("\$f$it", it, 0) }.toTypedArray()
    )

    // The eight condition flags will be stored in bits 0-7 for flags 0-7.
    @JvmStatic private val condition = Register("cf", 32, 0)
    const val conditionFlagCount = 8

    /**
     * Display Coprocessor1 register values for debugging.
     */
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
     * Set the value of the given FPU register name to the given new value.
     *
     * @param name The register name to set the value of.
     * @param value The new float value for the register.
     */
    @JvmStatic
    fun setRegisterToFloat(name: String, value: Float) {
        setRegisterToFloat(getRegisterNumber(name), value)
    }

    /**
     * Set the value of the given FPU register to the given new value.
     *
     * @param number The register number to set the value of.
     * @param value The new floating point value for the register.
     */
    @JvmStatic
    fun setRegisterToFloat(number: Int, value: Float) {
        if (number in registers.indices) registers[number].setValue(value.toRawIntBits())
    }

    /**
     * Set the value of the given FPU register name to the given 32-bit integer pattern.
     *
     * @param name The register name to set the value of.
     * @param value The desired new integer bit pattern value to set the register to.
     */
    @JvmStatic
    fun setRegisterToInt(name: String, value: Int) {
        setRegisterToInt(getRegisterNumber(name), value)
    }

    /**
     * Set the value of the given FPU register number to the given 32-bit integer pattern.
     *
     * @param number The register number to set the value of.
     * @param value The desired new integer bit pattern value to set the register to.
     */
    @JvmStatic
    fun setRegisterToInt(number: Int, value: Int) {
        if (number in registers.indices) registers[number].setValue(value)
    }

    /**
     * Set the value of the given FPU register to the double value given.
     * The register must be even-numbered, and the low-order 32 bits are placed in it.
     * The high-order 32 bits are placed in the odd-numbered register that follows it.
     *
     * @param number The register number to set the value of.
     * @param value The desired double value for the register.
     * @throws InvalidRegisterAccessException if the register [number] is invalid or odd-numbered.
     */
    @JvmStatic
    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToDouble(number: Int, value: Double) {
        if (number % 2 != 0 || number !in registers.indices || number + 1 !in registers.indices)
            throw InvalidRegisterAccessException()
        val bits = value.toRawLongBits()
        // Set high-order 32 bits in the odd register
        registers[number + 1].setValue(Binary.highOrderLongToInt(bits))
        // Set low-order 32 bits in the even register
        registers[number].setValue(Binary.lowOrderLongToInt(bits))
    }

    /**
     * Set the value of the FPU register given to the double value given.
     * The register must be even-numbered, and the low-order 32 bits are placed in it.
     * The high-order 32 bits are placed in the odd-numbered register that follows it.
     *
     * @param name The register name to set the value of.
     * @param value The desired double value for the register.
     * @throws InvalidRegisterAccessException if the register [name] is invalid or odd-numbered.
     */
    @JvmStatic
    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToDouble(name: String, value: Double) {
        setRegisterPairToDouble(getRegisterNumber(name), value)
    }

    /**
     * Set the value of the FPU register given to the long value containing a given 64-bit pattern.
     * The register must be even-numbered, and the low-order 32 bits from the long value are placed in it.
     * The high-order 32 bits from the long are placed in the odd-numbered register that follows it.
     *
     * @param number The register number to set the value of. Must be even.
     * @param value The long value to set the register pair to.
     * @throws InvalidRegisterAccessException if the register number is invalid or odd-numbered.
     */
    @JvmStatic
    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToLong(number: Int, value: Long) {
        if (number % 2 != 0 || number !in registers.indices || number + 1 !in registers.indices)
            throw InvalidRegisterAccessException()
        // Set high-order 32 bits
        registers[number + 1].setValue(Binary.highOrderLongToInt(value))
        // Set low-order 32 bits
        registers[number].setValue(Binary.lowOrderLongToInt(value))
    }

    /**
     * Set the value of the given FPU register pair to the given long value containing a 64-bit pattern.
     * The register must be even-numbered, and the low-order 32 bits from the long are placed in it.
     * The high-order 32 bits from the long are placed in the odd-numbered register that follows it.
     *
     * @param name The register name to set the value of.
     * @param value The desired long value containing the 64 bits to store in the register pair.
     * @throws InvalidRegisterAccessException if the register name is invalid or odd-numbered.
     */
    @JvmStatic
    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToLong(name: String, value: Long) {
        setRegisterPairToLong(getRegisterNumber(name), value)
    }

    /**
     * Get the float value stored in the given numbered FPU register.
     *
     * @param number The register number to get the value of.
     * @return The float value stored in the given register.
     */
    @JvmStatic
    fun getFloatFromRegister(number: Int): Float {
        var result = 0F
        if (number in registers.indices) result = registers[number].getValue().bitsToFloat()
        return result
    }

    /**
     * Get the float value stored in the given named FPU register.
     *
     * @param name The register name to get the value of.
     * @return The float value stored in the given named register.
     */
    @JvmStatic
    fun getFloatFromRegister(name: String): Float {
        return getFloatFromRegister(getRegisterNumber(name))
    }

    /**
     * Get the 32-bit integer bit pattern stored in the given numbered FPU register.
     *
     * @param number The register number to get the value of.
     * @return The integer bit pattern stored in the given numbered register.
     */
    @JvmStatic
    fun getIntFromRegister(number: Int): Int {
        var result = 0
        if (number in registers.indices) result = registers[number].getValue()
        return result
    }

    /**
     * Get the 32-bit integer pattern stored in the given named FPU register.
     *
     * @param name The register name to get the value of.
     * @return The integer bit pattern stored in the given named register.
     */
    @JvmStatic
    fun getIntFromRegister(name: String): Int {
        return getIntFromRegister(getRegisterNumber(name))
    }

    /**
     * Get the double value stored in the given even-numbered FPU register.
     *
     * @param number The register number to get the value of. Must be even.
     * @throws InvalidRegisterAccessException if the register ID is invalid or odd-numbered.
     */
    @JvmStatic
    @Throws(InvalidRegisterAccessException::class)
    fun getDoubleFromRegisterPair(number: Int): Double {
        if (number % 2 != 0 || number !in registers.indices || number + 1 !in registers.indices)
            throw InvalidRegisterAccessException()
        val longBits = Binary.twoIntegersToLong(registers[number + 1].getValue(), registers[number].getValue())
        return longBits.bitsToDouble()
    }

    /**
     * Gets the double value stored in the given even-numbered FPU register.
     *
     * @param name The register name to get the value of. Must be even.
     * @throws InvalidRegisterAccessException if the register is invalid or odd-numbered.
     */
    @JvmStatic
    @Throws(InvalidRegisterAccessException::class)
    fun getDoubleFromRegisterPair(name: String): Double {
        return getDoubleFromRegisterPair(getRegisterNumber(name))
    }

    /**
     * Get a long representing the double value stored in the given double-precision FPU register.
     * The register must be even-numbered.
     *
     * @param number The register number to get the value of. Must be even-numbered.
     * @throws InvalidRegisterAccessException if the register is invalid or odd-numbered.
     */
    @JvmStatic
    @Throws(InvalidRegisterAccessException::class)
    fun getLongFromRegisterPair(number: Int): Long {
        if (number % 2 != 0 || number !in registers.indices || number + 1 !in registers.indices)
            throw InvalidRegisterAccessException()
        return Binary.twoIntegersToLong(registers[number + 1].getValue(), registers[number].getValue())
    }

    /**
     * Get the double value stored in the given even-numbered FPU register.
     *
     * @param name The register name to get the value of. Must be even.
     * @throws InvalidRegisterAccessException if the register is invalid or odd-numbered.
     */
    @JvmStatic
    @Throws(InvalidRegisterAccessException::class)
    fun getLongFromRegisterPair(name: String): Long {
        return getLongFromRegisterPair(getRegisterNumber(name))
    }

    /**
     * Update the value of a numbered FPU register.
     * The registers hold int values only; use the helper methods to get or set an integer, long, float, or double.
     *
     * @param number The FPU register to set the value of.
     * @param newValue The desired new integer value for the register.
     * @return The old value of the register.
     */
    @JvmStatic
    fun updateRegister(number: Int, newValue: Int): Int {
        var oldValue = 0
        registers.firstOrNull {
            it.number == number
        }?.let {
            val isBackStepperEnabled = Globals.program.backSteppingEnabled()
            val backStepper = Globals.program.getBackStepper()
            oldValue = if (isBackStepperEnabled)
                backStepper!!.addCoprocessor1Restore(number, it.setValue(newValue))
            else it.setValue(newValue)
        }
        return oldValue
    }

    /**
     * Return the raw integer value of the numbered FPU register.
     * If you need a float, use [Int.bitsToFloat] to get the equivalent float value.
     *
     * @param number The numbered FPU register to get.
     * @return The raw integer value of the given register.
     */
    @JvmStatic
    fun getValue(number: Int): Int = registers[number].getValue()

    /**
     * Get the number of the register from its string name.
     *
     * @param name The name of the register to get the number for.
     * @return The number of the named register.
     */
    @JvmStatic
    fun getRegisterNumber(name: String): Int = registers.firstOrNull { it.name == name }?.number ?: -1

    /**
     * Get the Register object corresponding to the given name, or null if not found.
     *
     * @param name The FPU register name. Must be formatted as "$f(n)" where "(n)" is a number between 0 and 31.
     * @return The register object, or null if not found.
     */
    @JvmStatic
    fun getRegister(name: String): Register? {
        // Validate register name
        if (!name.startsWith("\$f") || name.length <= 3) return null
        // Try to get the value
        return try {
            registers[Binary.stringToInt(name.substring(2))]
        } catch (ignored: Exception) {
            // Handles both NumberFormatException and ArrayIndexOutOfBoundsException
            null
        }
    }

    /**
     * Reset the values of the FPU registers.
     */
    @JvmStatic
    fun resetRegisters() {
        registers.forEach(Register::resetValue)
        clearConditionFlags()
    }

    /**
     * Add an Observer to all FPU registers.
     *
     * @param observer The Observer to add to all FPU registers.
     */
    @JvmStatic
    fun addObserver(observer: Observer) {
        registers.forEach { it.addObserver(observer) }
    }

    /**
     * Remove an Observer from all FPU registers.
     *
     * @param observer The Observer to remove from all FPU registers.
     */
    @JvmStatic
    fun deleteObserver(observer: Observer) {
        registers.forEach { it.deleteObserver(observer) }
    }

    /**
     * Set a condition flag to 1 (true).
     *
     * @param flag The condition flag number, in range from 0 to 7.
     * @return The previous flag setting (0 or 1).
     */
    @JvmStatic
    fun setConditionFlag(flag: Int): Int {
        var oldValue = 0
        if (flag in 0..<conditionFlagCount) {
            val isBackSteppingEnabled = Globals.program.backSteppingEnabled()
            val backStepper = Globals.program.getBackStepper()
            oldValue = getConditionFlag(flag)
            condition.setValue(Binary.setBit(condition.getValue(), flag))
            if (isBackSteppingEnabled) {
                if (oldValue == 0) backStepper!!.addConditionFlagClear(flag)
                else backStepper!!.addConditionFlagSet(flag)
            }
        }
        return oldValue
    }

    /**
     * Set a condition flag to 0 (false).
     *
     * @param flag The condition flag number, in range from 0 to 7.
     * @return The previous flag setting (0 or 1).
     */
    @JvmStatic
    fun clearConditionFlag(flag: Int): Int {
        var oldValue = 0
        if (flag in 0..<conditionFlagCount) {
            val isBackStepperEnabled = Globals.program.backSteppingEnabled()
            val backStepper = Globals.program.getBackStepper()
            oldValue = getConditionFlag(flag)
            condition.setValue(Binary.clearBit(condition.getValue(), flag))
            if (isBackStepperEnabled) {
                if (oldValue == 0) backStepper!!.addConditionFlagClear(flag)
                else backStepper!!.addConditionFlagSet(flag)
            }
        }
        return oldValue
    }

    /**
     * Get the value of the specified condition flag (in range 0 to 7).
     *
     * @param flag The condition flag number, in range from 0 to 7.
     * @return Zero if the condition flag is false, 1 if the condition flag is true. If the flag is out of range, it
     * will instead return the value of flag 0.
     */
    @JvmStatic
    fun getConditionFlag(flag: Int): Int {
        var flag = flag
        if (flag !in 0..<conditionFlagCount) flag = 0
        return Binary.bitValue(condition.getValue(), flag)
    }

    /**
     * Get the whole condition flag register value.
     */
    @JvmStatic
    fun getConditionFlags(): Int = condition.getValue()

    /**
     * Clear all condition flags.
     */
    @JvmStatic
    fun clearConditionFlags() {
        condition.setValue(0)
    }

    /**
     * Set all condition flags.
     */
    @JvmStatic
    fun setConditionFlags() {
        condition.setValue(-1)
    }
}