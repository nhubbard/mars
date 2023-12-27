package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals.exitCode
import edu.missouristate.mars.Globals.gui
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue

/**
 * Service to exit the MIPS program with return value given in $a0.  Ignored if running from GUI.
 */
class SyscallExit2 : AbstractSyscall(17, "Exit2") {
    /**
     * Performs syscall function to exit the MIPS program with return value given in $a0.
     * If running in command mode, MARS will exit with that value.  If running under GUI,
     * return value is ignored.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        if (gui == null) exitCode = getValue(4)
        throw ProcessingException() // empty error list
    }
}