package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import javax.swing.JOptionPane;

/**
 * Service to display a message to user.
 */

public class SyscallMessageDialogDouble extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallMessageDialogDouble() {
        super(58, "MessageDialogDouble");
    }

    /**
     * System call to display a message to user.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments:
        //   $a0 = address of null-terminated string that is an information-type message to user
        //   $f12 = double value to display in string form after the first message
        // Output: none

        String message = new String(); // = "";
        int byteAddress = RegisterFile.getValue(4);
        char ch[] = {' '}; // Need an array to convert to String
        try {
            ch[0] = (char) Globals.memory.getByte(byteAddress);
            while (ch[0] != 0) // only uses single location ch[0]
            {
                message = message.concat(new String(ch)); // parameter to String constructor is a char[] array
                byteAddress++;
                ch[0] = (char) Globals.memory.getByte(byteAddress);
            }
        } catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
        }


        // Display the dialog.
        try {
            JOptionPane.showMessageDialog(null,
                    message + Double.toString(Coprocessor1.getDoubleFromRegisterPair("$f12")),
                    null,
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (InvalidRegisterAccessException e)   // register ID error in this method
        {
            RegisterFile.updateRegister(5, -1);  // set $a1 to -1 flag
            throw new ProcessingException(statement,
                    "invalid int reg. access during double input (syscall " + this.getNumber() + ")",
                    Exceptions.SYSCALL_EXCEPTION);
        }

    }

}
