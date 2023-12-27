package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import javax.swing.JOptionPane

/**
 * Service to display a message to user.
 */
class SyscallMessageDialogString : AbstractSyscall(59, "MessageDialogString") {
    /**
     * System call to display a message to user.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments:
        //   $a0 = address of null-terminated string that is an information-type message to user
        //   $a1 = address of null-terminated string to display after the first message
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

        var message2 = ""
        byteAddress = getValue(5)
        try {
            ch[0] = Globals.memory.getByte(byteAddress).toChar()
            while (ch[0].code != 0) {
                message2 += String(ch)
                byteAddress++
                ch[0] = Globals.memory.getByte(byteAddress).toChar()
            }
        } catch (e: AddressErrorException) {
            throw ProcessingException(statement, e)
        }

        // Display the dialog.
        JOptionPane.showMessageDialog(null, message + message2, null, JOptionPane.INFORMATION_MESSAGE)
    }
}
