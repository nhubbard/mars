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
    internal var hasWideRegisters = false
    val registers = Array(32) { Register("\$f$it", it, 0) }
    private val msaControl = Register("MSACSR", 1, 0)
    private val vectorHigh = LongArray(registers.size)
    private val fcsr = Register("FCSR", 31, FCSR_IEEE_2008)
    private val condition = Register("cf", registers.size, 0)
    private val registerEvents = EventEmitter<RegisterAccessNotice>()

    init {
        registers.forEach { it.addObserver(registerEvents::emit) }
        msaControl.addObserver(registerEvents::emit)
        fcsr.addObserver(registerEvents::emit)
        condition.addObserver(registerEvents::emit)
    }

    fun addRegistersObserver(listener: Consumer<RegisterAccessNotice>) {
        registerEvents.subscribe(listener)
    }

    fun deleteRegistersObserver(listener: Consumer<RegisterAccessNotice>) {
        registerEvents.unsubscribe(listener)
    }

    fun setRegisterToFloat(register: String?, value: Float) = setRegisterToFloat(getRegisterNumber(register), value)

    fun setRegisterToFloat(register: Int, value: Float) = setRegisterToInt(register, value.toRawBits())

    fun setRegisterToInt(register: String?, value: Int) = setRegisterToInt(getRegisterNumber(register), value)

    fun setRegisterToInt(register: Int, value: Int) {
        updateRegister(register, value)
    }

    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToDouble(register: Int, value: Double) = setRegisterPairToLong(register, value.toRawBits())

    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToDouble(register: String?, value: Double) = setRegisterPairToDouble(getRegisterNumber(register), value)

    /** In MARS32 the even register holds the low word and the high word is written first. */
    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToLong(register: Int, value: Long) {
        if (hasWideRegisters) {
            updateLongRegister(register, value)
            return
        }
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
        if (hasWideRegisters) return registers[register].longValue
        checkEvenRegister(register)
        return Binary.twoIntsToLong(registers[register + 1].value, registers[register].value)
    }

    @Throws(InvalidRegisterAccessException::class)
    fun getLongFromRegisterPair(register: String?): Long = getLongFromRegisterPair(getRegisterNumber(register))

    private fun checkEvenRegister(register: Int) {
        if (register % 2 != 0) throw InvalidRegisterAccessException()
    }

    fun getDoublewordNoNotify(number: Int): Long {
        if (hasWideRegisters) return registers[number].longValueNoNotify
        checkEvenRegister(number)
        return Binary.twoIntsToLong(registers[number + 1].valueNoNotify, registers[number].valueNoNotify)
    }

    /** Lane zero of each MSA register shares the existing scalar FPR storage. */
    internal fun readVector(number: Int): VectorRegister = VectorRegister(registers[number].longValue, vectorHigh[number])

    internal fun updateVector(number: Int, value: VectorRegister): VectorRegister {
        val previousHigh = vectorHigh[number]
        vectorHigh[number] = value.high
        val previousLow = updateLongRegister(number, value.low)
        return VectorRegister(previousLow, previousHigh)
    }

    internal fun updateVectorHigh(number: Int, value: Long) {
        vectorHigh[number] = value
    }

    internal fun updateLongRegister(number: Int, value: Long): Long = registers[number].setLongValue(value)

    fun updateRegister(number: Int, value: Int): Int = updateLowWord(number, value).toInt()

    internal fun updateLowWord(number: Int, value: Int): Long {
        val register = registers.getOrNull(number) ?: return 0L
        if (!hasWideRegisters) return register.setValue(value).toLong()
        return register.setLongValue((register.longValueNoNotify and -0x1_0000_0000L) or value.toUInt().toLong())
    }

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
        vectorHigh.fill(0)
        fcsr.resetValue()
        msaControl.resetValue()
        clearConditionFlags()
    }

    internal fun readControl(number: Int): Int? = when (number) {
        0 -> FIR
        26 -> fcsr.value and (CAUSE_MASK or FLAG_MASK)
        28 -> (fcsr.value and (ENABLE_MASK or ROUNDING_MASK)) or ((fcsr.valueNoNotify and FLUSH_SUBNORMALS) ushr 22)
        31 -> fcsr.value
        else -> null
    }

    /** Merge a write to FCSR or one of its aliases. FIR is read-only. */
    internal fun controlWriteValue(number: Int, bits: Int): Int? {
        val previous = fcsr.valueNoNotify
        return when (number) {
            26 -> replaceBits(previous, CAUSE_MASK or FLAG_MASK, bits)
            28 -> {
                val flush = (bits and 4) shl 22
                replaceBits(previous, ENABLE_MASK or ROUNDING_MASK or FLUSH_SUBNORMALS, (bits and (ENABLE_MASK or ROUNDING_MASK)) or flush)
            }
            31 -> (bits and (FLUSH_SUBNORMALS or CAUSE_MASK or ENABLE_MASK or FLAG_MASK or ROUNDING_MASK)) or FCSR_IEEE_2008
            else -> null
        }
    }

    internal val msaStatus: Int get() = msaControl.valueNoNotify

    internal fun readMsaControl(number: Int): Int = if (number == 1) msaControl.value else 0

    internal fun updateMsaStatus(bits: Int): Int = msaControl.setValue(bits and (FLUSH_SUBNORMALS or MSA_NON_TRAPPING or 0x3ffff))

    internal val floatingStatus: Int get() = fcsr.valueNoNotify

    internal fun updateFloatingStatus(bits: Int): Int = fcsr.setValue(bits)

    private fun replaceBits(original: Int, mask: Int, bits: Int): Int =
        (original and mask.inv()) or (bits and mask)

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
        // 2008 NaN encoding, 64-bit FPRs, longword, word, double, and single formats.
        private const val FIR = 0x00f3_0000
        private const val FCSR_IEEE_2008 = 0x000c_0000
        private const val ROUNDING_MASK = 0x3
        private const val FLAG_MASK = 0x7c
        private const val ENABLE_MASK = 0xf80
        internal const val CAUSE_MASK = 0x3f000
        internal const val FLUSH_SUBNORMALS = 1 shl 24
        internal const val MSA_NON_TRAPPING = 1 shl 18
        internal const val INEXACT = 1
        internal const val UNDERFLOW = 1 shl 1
        internal const val DIVIDE_BY_ZERO = 1 shl 3
        internal const val OVERFLOW = 1 shl 2
        internal const val UNDERFLOW_ENABLE = UNDERFLOW shl 7
        internal const val INVALID_OPERATION = 1 shl 4

        internal fun hasEnabledFloatingException(status: Int): Boolean =
            ((status ushr 12) and ((status ushr 7) or 0x20) and 0x3f) != 0

        private const val CONDITION_FLAG_COUNT = 8
        const val CONDITION_FLAG_MASK = (1 shl CONDITION_FLAG_COUNT) - 1
    }
}
