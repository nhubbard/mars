package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.RegisterFile;

import javax.swing.*;

/**
 * Service to display a message to user.
 */

public class SyscallMessageDialogFloat extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallMessageDialogFloat() {
        super(57, "MessageDialogFloat");
    }

    /**
     * System call to display a message to user.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments:
        //   $a0 = address of null-terminated string that is an information-type message to user
        //   $f12 = float value to display in string form after the first message
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
        JOptionPane.showMessageDialog(null,
                message + Float.toString(Coprocessor1.getFloatFromRegister("$f12")),
                null,
                JOptionPane.INFORMATION_MESSAGE);


    }

}
