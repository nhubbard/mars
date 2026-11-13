package edu.missouristate.mars.assembler

import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.Coprocessor1State
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile
import edu.missouristate.mars.mips.hardware.Register
import java.util.List.copyOf

/** Register spellings for the assembler and its editor integrations. */
object RegisterNames {
    private val integerBank = IntegerRegisterFile()
    private val floatBank = Coprocessor1State()
    private val systemBank = Coprocessor0State()

    @JvmStatic
    fun integer(name: String): Register? = integerBank.getUserRegister(name)

    @JvmStatic
    fun floating(name: String?): Register? = floatBank.getRegister(name)

    @JvmStatic
    fun coprocessor0(name: String?): Register? = systemBank.getRegister(name)

    @JvmStatic
    fun integerSpellings(): List<String> =
        copyOf(integerBank.registers.flatMap { listOf(it.name, "$${it.number}") })

    @JvmStatic
    fun floatingSpellings(): List<String> = copyOf(floatBank.registers.map(Register::name))

    @JvmStatic
    fun coprocessor0Spellings(): List<String> = copyOf(systemBank.registers.map { "$${it.number}" })
}
