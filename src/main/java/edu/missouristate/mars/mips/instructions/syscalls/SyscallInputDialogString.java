package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import javax.swing.JOptionPane;

/**
 * Service to input data.
 */

public class SyscallInputDialogString extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallInputDialogString() {
        super(54, "InputDialogString");
    }

    /**
     * System call to input data.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments:
        //    $a0 = address of null-terminated string that is the message to user
        //    $a1 = address of input buffer for the input string
        //    $a2 = maximum number of characters to read
        // Outputs:
        //    $a1 contains status value
        //       0: valid input data, correctly parsed
        //       -1: input data cannot be correctly parsed
        //       -2: Cancel was chosen
        //       -3: OK was chosen but no data had been input into field


        String message = new String(); // = "";
        int byteAddress = RegisterFile.getValue(4); // byteAddress of string is in $a0
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

        // Values returned by Java's InputDialog:
        // A null return value means that "Cancel" was chosen rather than OK.
        // An empty string returned (that is, inputString.length() of zero)
        // means that OK was chosen but no string was input.
        String inputString = null;
        inputString = JOptionPane.showInputDialog(message);
        byteAddress = RegisterFile.getValue(5); // byteAddress of string is in $a1
        int maxLength = RegisterFile.getValue(6); // input buffer size for input string is in $a2

        try {
            if (inputString == null)  // Cancel was chosen
            {
                RegisterFile.updateRegister(5, -2);  // set $a1 to -2 flag
            } else if (inputString.length() == 0)  // OK was chosen but there was no input
            {
                RegisterFile.updateRegister(5, -3);  // set $a1 to -3 flag
            } else {
                // The buffer will contain characters, a '\n' character, and the null character
                // Copy the input data to buffer as space permits
                for (int index = 0; (index < inputString.length()) && (index < maxLength - 1); index++) {
                    Globals.memory.setByte(byteAddress + index,
                            inputString.charAt(index));
                }
                if (inputString.length() < maxLength - 1) {
                    Globals.memory.setByte(byteAddress + (int) Math.min(inputString.length(), maxLength - 2), '\n');  // newline at string end
                }
                Globals.memory.setByte(byteAddress + (int) Math.min((inputString.length() + 1), maxLength - 1), 0);  // null char to end string

                if (inputString.length() > maxLength - 1) {
                    //  length of the input string exceeded the specified maximum
                    RegisterFile.updateRegister(5, -4);  // set $a1 to -4 flag
                } else {
                    RegisterFile.updateRegister(5, 0);  // set $a1 to 0 flag
                }
            } // end else

        } // end try
        catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
        }


    }

}
