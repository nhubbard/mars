package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import javax.swing.JOptionPane;

/**
 * Service to display a message to user.
 */

public class SyscallConfirmDialog extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallConfirmDialog() {
        super(50, "ConfirmDialog");
    }

    /**
     * System call to display a message to user.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments: $a0 = address of null-terminated string that is the message to user
        // Output: $a0 contains value of user-chosen option
        //   0: Yes
        //   1: No
        //   2: Cancel

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

        // update register $a0 with the value from showConfirmDialog.
        // showConfirmDialog returns an int with one of three possible values:
        //    0 ---> meaning Yes
        //    1 ---> meaning No
        //    2 ---> meaning Cancel
        RegisterFile.updateRegister(4, JOptionPane.showConfirmDialog(null, message));

    }

}
