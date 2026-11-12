package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.api.EventEmitter
import edu.missouristate.mars.api.MarsRegisterFile
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.util.Binary
import java.util.function.Consumer

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

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

/** One simulation's general-purpose registers, program counter, and multiplication registers. */
class IntegerRegisterFile(globalPointer: Int, stackPointer: Int, textBaseAddress: Int) : MarsRegisterFile {
    constructor() : this(
        MemoryConfigurations.getDefaultGlobalPointer(),
        MemoryConfigurations.getDefaultStackPointer(),
        MemoryConfigurations.getDefaultTextBaseAddress(),
    )

    val registers = Array(registerNames.size) { number ->
        val reset = when (number) {
            GLOBAL_POINTER_REGISTER -> globalPointer
            STACK_POINTER_REGISTER -> stackPointer
            else -> 0
        }
        Register("\$${registerNames[number]}", number, reset)
    }
    val programCounterRegister = Register("pc", PC_REGISTER, textBaseAddress)
    private val hi = Register("hi", HI_REGISTER, 0)
    private val lo = Register("lo", LO_REGISTER, 0)
    private val registerEvents = EventEmitter<RegisterAccessNotice>()

    init {
        registers.forEach { it.addObserver(registerEvents::emit) }
        hi.addObserver(registerEvents::emit)
        lo.addObserver(registerEvents::emit)
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

    fun updateRegister(number: Int, value: Int): Int {
        checkRegisterNumber(number)
        if (number == 0) return 0
        return registerAt(number).setValue(value)
    }

    fun updateRegister(name: String, value: Int) {
        if (name == "zero") {
            println("You can not change the value of the zero register.")
        } else {
            val index = registers.indexOfFirst { it.name == name }
            if (index >= 0) updateRegister(index, value)
        }
    }

    override fun getValue(register: Int): Int {
        checkRegisterNumber(register)
        return registerAt(register).value
    }

    /** Read a register for inspection without emitting an access event. */
    fun getValueNoNotify(number: Int): Int {
        checkRegisterNumber(number)
        return registerAt(number).valueNoNotify
    }

    fun getNumber(name: String?): Int = registers.firstOrNull { it.name == name }?.number ?: -1

    fun getUserRegister(name: String): Register? {
        if (name[0] != '$') return null
        val number = try {
            Binary.stringToInt(name.substring(1))
        } catch (_: NumberFormatException) {
            -1
        }
        return registers.getOrNull(number) ?: registers.firstOrNull { it.name == name }
    }

    fun initializeProgramCounter(value: Int) {
        programCounterRegister.setValue(value)
    }

    fun setProgramCounter(value: Int): Int = programCounterRegister.setValue(value)

    val programCounter: Int get() = programCounterRegister.value

    val initialProgramCounter: Int get() = programCounterRegister.resetValue

    fun resetRegisters() {
        registers.forEach(Register::resetValue)
        programCounterRegister.resetValue()
        hi.resetValue()
        lo.resetValue()
    }

    fun incrementPC() {
        programCounterRegister.setValue(programCounter + Instruction.INSTRUCTION_LENGTH)
    }

    override fun setValue(register: Int, value: Int) {
        updateRegister(register, value)
    }

    override fun getPC(): Int = programCounter

    override fun reset() = resetRegisters()

    private fun checkRegisterNumber(number: Int) {
        require(number in 0..LO_REGISTER) { "Invalid register number: $number" }
    }

    private fun registerAt(number: Int): Register = when (number) {
        PC_REGISTER -> programCounterRegister
        HI_REGISTER -> hi
        LO_REGISTER -> lo
        else -> registers[number]
    }

    companion object {
        const val GLOBAL_POINTER_REGISTER = 28
        const val STACK_POINTER_REGISTER = 29
        private const val PC_REGISTER = 32
        const val HI_REGISTER = 33
        const val LO_REGISTER = 34

        private val registerNames = listOf(
            "zero", "at", "v0", "v1", "a0", "a1", "a2", "a3",
            "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7",
            "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7",
            "t8", "t9", "k0", "k1", "gp", "sp", "fp", "ra",
        )
    }
}
