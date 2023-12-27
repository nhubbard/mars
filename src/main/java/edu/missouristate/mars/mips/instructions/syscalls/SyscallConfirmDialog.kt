package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import javax.swing.JOptionPane

/**
 * Service to display a message to user.
 */
class SyscallConfirmDialog : AbstractSyscall(50, "ConfirmDialog") {
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments: $a0 = address of null-terminated string that is the message to user
        // Output: $a0 contains value of user-chosen option
        //   0: Yes
        //   1: No
        //   2: Cancel
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

        // update register $a0 with the value from showConfirmDialog.
        // showConfirmDialog returns an int with one of three possible values:
        //    0 ---> meaning Yes
        //    1 ---> meaning No
        //    2 ---> meaning Cancel
        updateRegister(4, JOptionPane.showConfirmDialog(null, message))
    }
}
