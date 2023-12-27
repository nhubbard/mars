package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import javax.swing.JOptionPane

/**
 * Service to input data.
 */
class SyscallInputDialogInt : AbstractSyscall(51, "InputDialogInt") {
    /**
     * System call to input data.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments: $a0 = address of null-terminated string that is the message to user
        // Outputs:
        //    $a0 contains value of int read
        //    $a1 contains status value
        //       0: valid input data, correctly parsed
        //       -1: input data cannot be correctly parsed
        //       -2: Cancel was chosen
        //       -3: OK was chosen but no data had been input into field
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

        // Values returned by Java's InputDialog:
        // A null return value means that "Cancel" was chosen rather than OK.
        // An empty string returned (that is, inputValue.length() of zero)
        // means that OK was chosen but no string was input.
        val inputValue = JOptionPane.showInputDialog(message)
        if (inputValue == null) {
            // Cancel was chosen
            updateRegister(4, 0) // set $a0 to zero
            updateRegister(5, -2) // set $a1 to -2 flag
        } else if (inputValue.isEmpty()) {
            // OK was chosen but there was no input
            updateRegister(4, 0) // set $a0 to zero
            updateRegister(5, -3) // set $a1 to -3 flag
        } else {
            try {
                val i = inputValue.toInt()
                // Successful parse of valid input data
                updateRegister(4, i) // set $a0 to the data read
                updateRegister(5, 0) // set $a1 to valid flag
            } catch (e: NumberFormatException) {
                // Unsuccessful parse of input data
                updateRegister(4, 0) // set $a0 to zero
                updateRegister(5, -1) // set $a1 to -1 flag
            }
        } // end else
    }
}
