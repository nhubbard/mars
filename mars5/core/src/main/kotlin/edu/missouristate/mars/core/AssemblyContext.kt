package edu.missouristate.mars.core

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.api.MarsSettings
import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.Register
import edu.missouristate.mars.mips.instructions.InstructionSet
import kotlin.jvm.JvmRecord

/** Assembly dependencies shared by source files, includes, and pseudo-instructions. */
@JvmRecord
data class AssemblyContext(val session: MarsContext) {
    fun sharesSession(other: AssemblyContext): Boolean = session === other.session

    fun memory(): MemoryState = session.memory

    fun symbols(): SymbolTable = session.symbolTable

    fun instructions(): InstructionSet = session.instructionSet

    fun settings(): MarsSettings = session.settings

    fun newErrorList(): ErrorList = ErrorList(session.settings)

    fun bareMachineEnabled(): Boolean = session.settings.isBareMachineEnabled

    fun debug(): Boolean = false

    fun displayAddressesInHex(): Boolean = session.settings.displayAddressesInHex()

    fun tokenizer(program: MIPSprogram): Tokenizer = Tokenizer(program, instructions())

    fun integerRegister(name: String): Register? = session.registerFile.getUserRegister(name)

    fun integerRegisterNumber(name: String?): Int = session.registerFile.getNumber(name)

    fun floatingRegisterNumber(name: String?): Int = session.coprocessor1.getRegisterNumber(name)

    fun clearMemory() {
        session.backStepper.clear()
        session.memory.clear()
    }

    fun beginAssembly(program: MIPSprogram) {
        session.beginAssembly(program)
    }

    fun finishAssembly(program: MIPSprogram) {
        session.finishAssembly(program)
    }
}
