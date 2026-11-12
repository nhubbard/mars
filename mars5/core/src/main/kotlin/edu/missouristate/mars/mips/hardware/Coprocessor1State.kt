package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.api.EventEmitter
import edu.missouristate.mars.util.Binary
import java.util.function.Consumer

/*
Copyright (c) 2003-2009,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** Owns one simulation's floating-point registers, condition flags, and access listeners. */
class Coprocessor1State {
    val registers = Array(32) { Register("\$f$it", it, 0) }
    private val condition = Register("cf", registers.size, 0)
    private val registerEvents = EventEmitter<RegisterAccessNotice>()

    init {
        registers.forEach { it.addObserver(registerEvents::emit) }
        condition.addObserver(registerEvents::emit)
    }

    fun addRegistersObserver(listener: Consumer<RegisterAccessNotice>) {
        registerEvents.subscribe(listener)
    }

    fun deleteRegistersObserver(listener: Consumer<RegisterAccessNotice>) {
        registerEvents.unsubscribe(listener)
    }

    fun showRegisters() {
        for (register in registers) {
            println("Name: ${register.name}")
            println("Number: ${register.number}")
            println("Value: ${register.value}")
            println()
        }
    }

    fun setRegisterToFloat(register: String?, value: Float) = setRegisterToFloat(getRegisterNumber(register), value)

    fun setRegisterToFloat(register: Int, value: Float) = setRegisterToInt(register, value.toRawBits())

    fun setRegisterToInt(register: String?, value: Int) = setRegisterToInt(getRegisterNumber(register), value)

    fun setRegisterToInt(register: Int, value: Int) {
        registers.getOrNull(register)?.setValue(value)
    }

    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToDouble(register: Int, value: Double) = setRegisterPairToLong(register, value.toRawBits())

    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToDouble(register: String?, value: Double) = setRegisterPairToDouble(getRegisterNumber(register), value)

    /** The even register holds the low word. Observers see the odd register accessed first. */
    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToLong(register: Int, value: Long) {
        checkEvenRegister(register)
        registers[register + 1].setValue(Binary.highOrderLongToInt(value))
        registers[register].setValue(Binary.lowOrderLongToInt(value))
    }

    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToLong(register: String?, value: Long) = setRegisterPairToLong(getRegisterNumber(register), value)

    fun getFloatFromRegister(register: Int): Float = Float.fromBits(getIntFromRegister(register))

    fun getFloatFromRegister(register: String?): Float = getFloatFromRegister(getRegisterNumber(register))

    fun getIntFromRegister(register: Int): Int = registers.getOrNull(register)?.value ?: 0

    fun getIntFromRegister(register: String?): Int = getIntFromRegister(getRegisterNumber(register))

    @Throws(InvalidRegisterAccessException::class)
    fun getDoubleFromRegisterPair(register: Int): Double = Double.fromBits(getLongFromRegisterPair(register))

    @Throws(InvalidRegisterAccessException::class)
    fun getDoubleFromRegisterPair(register: String?): Double = getDoubleFromRegisterPair(getRegisterNumber(register))

    @Throws(InvalidRegisterAccessException::class)
    fun getLongFromRegisterPair(register: Int): Long {
        checkEvenRegister(register)
        return Binary.twoIntsToLong(registers[register + 1].value, registers[register].value)
    }

    @Throws(InvalidRegisterAccessException::class)
    fun getLongFromRegisterPair(register: String?): Long = getLongFromRegisterPair(getRegisterNumber(register))

    private fun checkEvenRegister(register: Int) {
        if (register % 2 != 0) throw InvalidRegisterAccessException()
    }

    fun updateRegister(number: Int, value: Int): Int = registers.firstOrNull { it.number == number }?.setValue(value) ?: 0

    fun getValue(number: Int): Int = registers[number].value

    fun getRegisterNumber(name: String?): Int = getRegister(name)?.number ?: -1

    fun getRegister(name: String?): Register? {
        if (name == null || name.length <= 2 || !name.startsWith("\$f")) return null
        val number = try {
            Binary.stringToInt(name.substring(2))
        } catch (_: NumberFormatException) {
            return null
        }
        return registers.getOrNull(number)
    }

    fun resetRegisters() {
        registers.forEach(Register::resetValue)
        clearConditionFlags()
    }

    fun setConditionFlag(flag: Int): Int {
        if (flag !in 0 until conditionFlagCount) return 0
        val previous = getConditionFlag(flag)
        condition.setValue(Binary.setBit(condition.value, flag))
        return previous
    }

    fun clearConditionFlag(flag: Int): Int {
        if (flag !in 0 until conditionFlagCount) return 0
        val previous = getConditionFlag(flag)
        condition.setValue(Binary.clearBit(condition.value, flag))
        return previous
    }

    fun getConditionFlag(flag: Int): Int = Binary.bitValue(condition.value, if (flag in 0 until conditionFlagCount) flag else 0)

    val conditionFlags: Int get() = condition.value

    val conditionFlagsNoNotify: Int get() = condition.valueNoNotify

    fun setConditionFlagBits(bits: Int) {
        condition.setValue((condition.valueNoNotify and CONDITION_FLAG_MASK.inv()) or (bits and CONDITION_FLAG_MASK))
    }

    fun clearConditionFlags() {
        condition.setValue(0)
    }

    fun setConditionFlags() {
        condition.setValue(-1)
    }

    val conditionFlagCount: Int get() = CONDITION_FLAG_COUNT

    companion object {
        private const val CONDITION_FLAG_COUNT = 8
        const val CONDITION_FLAG_MASK = (1 shl CONDITION_FLAG_COUNT) - 1
    }
}
