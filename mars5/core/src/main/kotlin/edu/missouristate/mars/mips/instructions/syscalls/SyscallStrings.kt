package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.mips.hardware.AddressErrorException

/** Reads a null-terminated syscall argument using the simulator's normal memory reads. */
internal fun InstructionContext.readSyscallString(statement: ProgramStatement?, address: Int): String {
    val memory = memory()
    var cursor = address
    return try {
        buildString {
            while (true) {
                val character = memory.getByte(cursor++).toChar()
                if (character == '\u0000') break
                append(character)
            }
        }
    } catch (failure: AddressErrorException) {
        throw ProcessingException(this, statement, failure)
    }
}
