package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import edu.missouristate.mars.util.Binary.highOrderLongToInt
import edu.missouristate.mars.util.Binary.lowOrderLongToInt
import java.util.*

class SyscallTime : AbstractSyscall(30, "Time") {
    /**
     * Performs syscall function to place current system time into $a0 (low-order 32 bits)
     * and $a1 (high-order 32 bits).
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        val value = Date().time
        updateRegister(4, lowOrderLongToInt(value)) // $a0
        updateRegister(5, highOrderLongToInt(value)) // $a1
    }
}