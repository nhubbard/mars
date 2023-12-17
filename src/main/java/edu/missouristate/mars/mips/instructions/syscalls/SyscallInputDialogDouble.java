package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.simulator.Exceptions;

import javax.swing.*;

/**
 * Service to input data.
 */

public class SyscallInputDialogDouble extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallInputDialogDouble() {
        super(53, "InputDialogDouble");
    }

    /**
     * System call to input data.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments: $a0 = address of null-terminated string that is the message to user
        // Outputs:
        //    $f0 and $f1 contains value of double read. $f1 contains high order word of the double.
        //    $a1 contains status value
        //       0: valid input data, correctly parsed
        //       -1: input data cannot be correctly parsed
        //       -2: Cancel was chosen
        //       -3: OK was chosen but no data had been input into field


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

        // Values returned by Java's InputDialog:
        // A null return value means that "Cancel" was chosen rather than OK.
        // An empty string returned (that is, inputValue.length() of zero)
        // means that OK was chosen but no string was input.
        String inputValue = null;
        inputValue = JOptionPane.showInputDialog(message);

        try {
            Coprocessor1.setRegisterPairToDouble(0, 0.0);  // set $f0 to zero
            if (inputValue == null)  // Cancel was chosen
            {
                RegisterFile.updateRegister(5, -2);  // set $a1 to -2 flag
            } else if (inputValue.length() == 0)  // OK was chosen but there was no input
            {
                RegisterFile.updateRegister(5, -3);  // set $a1 to -3 flag
            } else {
                double doubleValue = Double.parseDouble(inputValue);

                // Successful parse of valid input data
                Coprocessor1.setRegisterPairToDouble(0, doubleValue);  // set $f0 to input data
                RegisterFile.updateRegister(5, 0);  // set $a1 to valid flag

            }

        } // end try block

        catch (InvalidRegisterAccessException e)   // register ID error in this method
        {
            RegisterFile.updateRegister(5, -1);  // set $a1 to -1 flag
            throw new ProcessingException(statement,
                    "invalid int reg. access during double input (syscall " + this.getNumber() + ")",
                    Exceptions.SYSCALL_EXCEPTION);
        } catch (NumberFormatException e)    // Unsuccessful parse of input data
        {
            RegisterFile.updateRegister(5, -1);  // set $a1 to -1 flag
                   /*  Don't throw exception because returning a status flag
                   throw new ProcessingException(statement,
                      "invalid float input (syscall "+this.getNumber()+")",
						          Exceptions.SYSCALL_EXCEPTION);
                   */
        }


    }

}
