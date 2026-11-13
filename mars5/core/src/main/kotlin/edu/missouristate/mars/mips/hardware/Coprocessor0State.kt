package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.api.EventEmitter
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

/** Owns one simulation's interrupt and exception registers. */
class Coprocessor0State {
    val registers = arrayOf(
        Register("$8 (vaddr)", VADDR, 0),
        Register("$12 (status)", STATUS, DEFAULT_STATUS_VALUE),
        Register("$13 (cause)", CAUSE, 0),
        Register("$14 (epc)", EPC, 0),
    )
    private val registerEvents = EventEmitter<RegisterAccessNotice>()

    init {
        registers.forEach { it.addObserver(registerEvents::emit) }
    }

    fun addRegistersObserver(listener: Consumer<RegisterAccessNotice>) {
        registerEvents.subscribe(listener)
    }

    fun deleteRegistersObserver(listener: Consumer<RegisterAccessNotice>) {
        registerEvents.unsubscribe(listener)
    }

    fun updateRegister(name: String?, value: Int): Int {
        val register = getRegister(name) ?: return 0
        // Named updates emit the same read followed by write that existing observers see.
        val previous = register.value
        register.setValue(value)
        return previous
    }

    fun updateRegister(number: Int, value: Int): Int = getRegister(number)?.setValue(value) ?: 0

    fun getValue(number: Int): Int = getRegister(number)?.value ?: 0

    fun getRegister(number: Int): Register? = registers.firstOrNull { it.number == number }

    fun getNumber(name: String?): Int = getRegister(name)?.number ?: -1

    /** An architectural register number does not correspond to its index in this array. */
    fun getRegisterPosition(register: Register?): Int = registers.indexOfFirst { it === register }

    fun getRegister(name: String?): Register? = registers.firstOrNull { it.name == name || "$${it.number}" == name }

    fun resetRegisters() {
        registers.forEach(Register::resetValue)
    }

    companion object {
        const val VADDR = 8
        const val STATUS = 12
        const val CAUSE = 13
        const val EPC = 14
        const val EXCEPTION_LEVEL = 1

        // Enable interrupts and user mode, enable all interrupt levels, and clear exception level.
        const val DEFAULT_STATUS_VALUE = 0x0000ff11
    }
}
