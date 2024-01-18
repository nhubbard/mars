/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
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

import edu.missouristate.mars.Globals
import edu.missouristate.mars.CoreSettings
import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.mips.instructions.Instruction
import java.util.Observer

/**
 * Represents non-FPU MIPS registers.
 */
object RegisterFile {
    const val GLOBAL_POINTER_REGISTER = 28
    const val STACK_POINTER_REGISTER = 29

    @JvmStatic
    val registers = arrayOf(
        Register("\$zero", 0, 0),
        Register("\$at", 1, 0),
        Register("\$v0", 2, 0),
        Register("\$v1", 3, 0),
        Register("\$a0", 4, 0),
        Register("\$a1", 5, 0),
        Register("\$a2", 6, 0),
        Register("\$a3", 7, 0),
        Register("\$t0", 8, 0),
        Register("\$t1", 9, 0),
        Register("\$t2", 10, 0),
        Register("\$t3", 11, 0),
        Register("\$t4", 12, 0),
        Register("\$t5", 13, 0),
        Register("\$t6", 14, 0),
        Register("\$t7", 15, 0),
        Register("\$s0", 16, 0),
        Register("\$s1", 17, 0),
        Register("\$s2", 18, 0),
        Register("\$s3", 19, 0),
        Register("\$s4", 20, 0),
        Register("\$s5", 21, 0),
        Register("\$s6", 22, 0),
        Register("\$s7", 23, 0),
        Register("\$t8", 24, 0),
        Register("\$t9", 25, 0),
        Register("\$k0", 26, 0),
        Register("\$k1", 27, 0),
        Register("\$gp", GLOBAL_POINTER_REGISTER, Memory.globalPointer),
        Register("\$sp", STACK_POINTER_REGISTER, Memory.stackPointer),
        Register("\$fp", 30, 0),
        Register("\$ra", 31, 0)
    )

    @JvmStatic val programCounter = Register("pc", 32, Memory.textBaseAddress)
    // Internal registers with arbitrary numbers
    @JvmStatic val hi = Register("hi", 33, 0)
    @JvmStatic val lo = Register("lo", 34, 0)

    // Create some internal indexes to make lookup operations faster
    @JvmStatic private val nameToNumberMap = hashMapOf(
        *registers.map { it.name.drop(1) to it.number }.toTypedArray(),
        "hi" to hi.number,
        "lo" to lo.number
    )
    @JvmStatic private val nameToRegisterMap = hashMapOf(
        *registers.map { it.name to it }.toTypedArray(),
        "hi" to hi,
        "lo" to lo
    )
    @JvmStatic private val numberedNameToRegisterMap = hashMapOf(
        *registers.map { "$${it.number}" to it }.toTypedArray(),
        "\$33" to hi,
        "\$34" to lo
    )

    /**
     * Display register values for debugging.
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
     * Update the register numbered [number] with the new value [value].
     *
     * @param number The register number to set the value of.
     * @param value The desired value for the register.
     * @return The old value of the register; may be 0 if the register doesn't exist.
     */
    @JvmStatic
    fun updateRegister(number: Int, value: Int): Int {
        val isBackStepperEnabled = Globals.settings.getBackSteppingEnabled()
        val backStepper = Globals.program.getBackStepper()
        var oldValue = 0
        val register = when (number) {
            0 -> {
                println("You cannot change the value of the zero register.")
                null
            }
            in 1..<registers.size -> registers.firstOrNull { it.number == number }
            33 -> hi
            34 -> lo
            // There isn't a register numbered beyond 34.
            else -> return 0
        }
        register?.let {
            oldValue =
                if (isBackStepperEnabled) backStepper!!.addRegisterFileRestore(number, it.setValue(value))
                else it.setValue(value)
        }
        return oldValue
    }

    /**
     * Update the register named [name] with the new value [value].
     *
     * @param name The register name to set the value of.
     * @param value The desired value for the register.
     */
    @JvmStatic
    fun updateRegister(name: String, value: Int) {
        nameToNumberMap[name]?.let {
            updateRegister(it, value)
        }
    }

    /**
     * Get the value of a register by number.
     *
     * @param number The register number.
     * @return The value of the given register.
     */
    @JvmStatic
    fun getValue(number: Int): Int = when (number) {
        33 -> hi.getValue()
        34 -> lo.getValue()
        else -> registers[number].getValue()
    }

    /**
     * Get the number of a register.
     *
     * @param name The name of the register.
     * @return The number of the register, or -1 if not found.
     */
    @JvmStatic
    fun getNumber(name: String) = nameToNumberMap[name] ?: -1

    /**
     * Get the Register object corresponding to the given name.
     *
     * @param name The name of the register; either in numbered (ex. `$0`) or named (ex. `$zero`) format.
     * @return The Register object, or null.
     */
    @JvmStatic
    fun getUserRegister(name: String): Register? = nameToRegisterMap[name] ?: numberedNameToRegisterMap[name]

    /**
     * Initialize the program counter.
     * Do **not** use this to implement jumps and branches, as it will **not** record a backstep entry with the restore
     * value!
     * If you need backstepping capability, use [setProgramCounter] instead.
     */
    @JvmStatic
    fun initializeProgramCounter(value: Int) {
        programCounter.setValue(value)
    }

    /**
     * Initialize the program counter to either the default reset value,
     * or the address associated with the source program global label "main" if it exists as a text segment label *and*
     * the [edu.missouristate.mars.CoreSettings.START_AT_MAIN] setting is enabled.
     *
     * @param startAtMain If `true`, this function will set the program counter to the address of the statement labeled
     * "main", or another defined start label, if defined.
     * If set to `false`, the program counter will be set to the default value.
     * Defaults to false.
     */
    @JvmStatic
    @JvmOverloads
    fun initializeProgramCounter(startAtMain: Boolean = false) {
        val mainAddr = Globals.symbolTable.getAddressOrNull(SymbolTable.startLabel)
        if (startAtMain && mainAddr != null && (Memory.inTextSegment(mainAddr) || Memory.inKernelTextSegment(mainAddr)))
            initializeProgramCounter(mainAddr)
        else initializeProgramCounter(programCounter.getResetValue())
    }

    /**
     * Set the program counter and create a backstep entry.
     * Ordinary program counter updates should be done using the [incrementPC] function.
     * Use this only when processing jumps and branches.
     *
     * @param value The value to set the program counter to.
     * @return The previous value of the program counter.
     */
    @JvmStatic
    fun setProgramCounter(value: Int): Int {
        val oldValue = programCounter.getValue()
        programCounter.setValue(value)
        if (Globals.settings.getBackSteppingEnabled())
            Globals.program.getBackStepper()!!.addPCRestore(oldValue)
        return oldValue
    }

    /**
     * Return the value of the program counter.
     */
    @Deprecated(
        "Directly access the program counter instead.",
        ReplaceWith("programCounter.getValue()"),
        DeprecationLevel.ERROR
    )
    fun getProgramCounterValue() = programCounter.getValue()

    /**
     * Return the Register object for the program counter.
     */
    @Deprecated(
        "Directly access the program counter instead.",
        ReplaceWith("programCounter"),
        DeprecationLevel.ERROR
    )
    fun getProgramCounterRegister() = programCounter

    /**
     * Return the reset value for the program counter.
     */
    @Deprecated(
        "Directly access the program counter instead.",
        ReplaceWith("programCounter.getResetValue()"),
        DeprecationLevel.ERROR
    )
    fun getInitialProgramCounter() = programCounter.getResetValue()

    /**
     * Reinitialize the main register file.
     *
     * @note Do ***not*** call this from command-line mode! It uses global settings from the registry, instead of
     * the local values from the command line. It *can* be called from tools running in standalone mode, such as tools
     * that descend from [edu.missouristate.mars.tools.AbstractMarsToolAndApplication].
     */
    @JvmStatic
    fun resetRegisters() {
        for (register in registers) register.resetValue()
        initializeProgramCounter(Globals.settings.getBooleanSetting(CoreSettings.START_AT_MAIN))
        hi.resetValue()
        lo.resetValue()
    }

    /**
     * Increment the program counter for normal execution (not jumps or branches).
     */
    @JvmStatic
    fun incrementPC() {
        programCounter.setValue(programCounter.getValue() + Instruction.INSTRUCTION_LENGTH)
    }

    /**
     * Add an Observer to all registers.
     */
    @JvmStatic
    fun addObserver(observer: Observer) {
        for (register in registers) register.addObserver(observer)
        hi.addObserver(observer)
        lo.addObserver(observer)
    }

    @Deprecated(
        "Renamed to addObserver.",
        ReplaceWith("addObserver(observer)"),
        DeprecationLevel.ERROR
    )
    @JvmStatic
    fun addRegisterObserver(observer: Observer) = addObserver(observer)

    /**
     * Remove an Observer from all registers.
     */
    @JvmStatic
    fun deleteObserver(observer: Observer) {
        for (register in registers) register.deleteObserver(observer)
        hi.deleteObserver(observer)
        lo.deleteObserver(observer)
    }

    @Deprecated(
        "Renamed to deleteObserver.",
        ReplaceWith("deleteObserver(observer)"),
        DeprecationLevel.ERROR
    )
    @JvmStatic
    fun deleteRegisterObserver(observer: Observer) = deleteObserver(observer)
}