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
class SyscallMessageDialog : AbstractSyscall(55, "MessageDialog") {
    /**
     * System call to display a message to user.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments:
        //   $a0 = address of null-terminated string that is the message to user
        //   $a1 = the type of the message to the user, which is one of:
        //       1: error message
        //       2: information message
        //       3: warning message
        //       4: question message
        //       other: plain message
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
        var msgType = getValue(5)
        if (msgType < 0 || msgType > 3) msgType = -1
        JOptionPane.showMessageDialog(null, message, null, msgType)
    }
}
