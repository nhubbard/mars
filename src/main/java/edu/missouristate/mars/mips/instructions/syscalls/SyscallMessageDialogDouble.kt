package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor1.getDoubleFromRegisterPair
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import edu.missouristate.mars.simulator.Exceptions
import javax.swing.JOptionPane

/**
 * Service to display a message to user.
 */
class SyscallMessageDialogDouble : AbstractSyscall(58, "MessageDialogDouble") {
    /**
     * System call to display a message to user.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments:
        //   $a0 = address of null-terminated string that is an information-type message to user
        //   $f12 = double value to display in string form after the first message
        // Output: none
        var message = ""
        var byteAddress = getValue(4)
        val ch = charArrayOf(' ')
        try {
            ch[0] = Globals.memory.getByte(byteAddress).toChar()
            while (ch[0].code != 0) {
                message += String(ch)
                byteAddress++
                ch[0] = Globals.memory.getByte(byteAddress).toChar()
            }
        } catch (e: AddressErrorException) {
            throw ProcessingException(statement, e)
        }
        // Display the dialog.
        try {
            JOptionPane.showMessageDialog(
                null,
                message + getDoubleFromRegisterPair("\$f12"),
                null,
                JOptionPane.INFORMATION_MESSAGE
            )
        } catch (e: InvalidRegisterAccessException) {
            updateRegister(5, -1) // set $a1 to -1 flag
            throw ProcessingException(
                statement,
                "Invalid integer register access during double input (syscall $number)!",
                Exceptions.SYSCALL_EXCEPTION
            )
        }
    }
}
