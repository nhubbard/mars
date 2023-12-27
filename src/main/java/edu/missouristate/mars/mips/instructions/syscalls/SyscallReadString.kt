package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.util.SystemIO
import kotlin.math.min

/**
 * Service to read console input string into buffer starting at address in $a0.
 */
class SyscallReadString : AbstractSyscall(8, "ReadString") {
    /**
     * Performs syscall function to read console input string into buffer starting at address in $a0.
     * Follows semantics of UNIX 'fgets'.
     * For specified length n, string can be no longer than n-1.
     * If less than that, add newline to end.
     * In either case, then pad with null byte.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        val inputString: String
        val buf = getValue(4) // buf addr in $a0
        var maxLength = getValue(5) - 1 // $a1
        var addNullByte = true
        // Guard against negative maxLength.  DPS 13-July-2011
        if (maxLength < 0) {
            maxLength = 0
            addNullByte = false
        }
        inputString = SystemIO.readString(this.number, maxLength)
        var stringLength = min(maxLength.toDouble(), inputString.length.toDouble()).toInt()
        try {
            for (index in 0 until stringLength) {
                Globals.memory.setByte(buf + index, inputString[index].code)
            }
            if (stringLength < maxLength) {
                Globals.memory.setByte(buf + stringLength, '\n'.code)
                stringLength++
            }
            if (addNullByte) Globals.memory.setByte(buf + stringLength, 0)
        } catch (e: AddressErrorException) {
            throw ProcessingException(statement, e)
        }
    }
}