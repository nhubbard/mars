package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import javax.swing.JOptionPane;

/**
 * Service to display a message to user.
 */

public class SyscallMessageDialog extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallMessageDialog() {
        super(55, "MessageDialog");
    }

    /**
     * System call to display a message to user.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments:
        //   $a0 = address of null-terminated string that is the message to user
        //   $a1 = the type of the message to the user, which is one of:
        //       1: error message
        //       2: information message
        //       3: warning message
        //       4: question message
        //       other: plain message
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
        int msgType = RegisterFile.getValue(5);
        if (msgType < 0 || msgType > 3)
            msgType = -1; // See values in http://java.sun.com/j2se/1.5.0/docs/api/constant-values.html
        JOptionPane.showMessageDialog(null, message, null, msgType);


    }

}
