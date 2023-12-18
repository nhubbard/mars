package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.RegisterFile;

import javax.swing.*;

/**
 * Service to input data.
 */

public class SyscallInputDialogInt extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallInputDialogInt() {
        super(51, "InputDialogInt");
    }

    /**
     * System call to input data.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments: $a0 = address of null-terminated string that is the message to user
        // Outputs:
        //    $a0 contains value of int read
        //    $a1 contains status value
        //       0: valid input data, correctly parsed
        //       -1: input data cannot be correctly parsed
        //       -2: Cancel was chosen
        //       -3: OK was chosen but no data had been input into field


        String message = ""; // = "";
        int byteAddress = RegisterFile.getValue(4);
        char[] ch = {' '}; // Need an array to convert to String
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

        // Values returned by Java's InputDialog:
        // A null return value means that "Cancel" was chosen rather than OK.
        // An empty string returned (that is, inputValue.length() of zero)
        // means that OK was chosen but no string was input.
        String inputValue;
        inputValue = JOptionPane.showInputDialog(message);
        if (inputValue == null)  // Cancel was chosen
        {
            RegisterFile.updateRegister(4, 0);  // set $a0 to zero
            RegisterFile.updateRegister(5, -2);  // set $a1 to -2 flag
        } else if (inputValue.isEmpty())  // OK was chosen but there was no input
        {
            RegisterFile.updateRegister(4, 0);  // set $a0 to zero
            RegisterFile.updateRegister(5, -3);  // set $a1 to -3 flag
        } else {
            try {
                int i = Integer.parseInt(inputValue);

                // Successful parse of valid input data
                RegisterFile.updateRegister(4, i);  // set $a0 to the data read
                RegisterFile.updateRegister(5, 0);  // set $a1 to valid flag
            } catch (NumberFormatException e) {
                // Unsuccessful parse of input data
                RegisterFile.updateRegister(4, 0);  // set $a0 to zero
                RegisterFile.updateRegister(5, -1);  // set $a1 to -1 flag

            }

        } // end else

    }

}
