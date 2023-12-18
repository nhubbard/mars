package edu.missouristate.mars;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.simulator.*;

/**
 * Class to represent error that occurs while assembling or running a MIPS program.
 *
 * @author Pete Sanderson
 * @version August 2003
 **/

public class ProcessingException extends Exception {
    private final ErrorList errs;

    /**
     * Constructor for ProcessingException.
     *
     * @param e An ErrorList which is an ArrayList of ErrorMessage objects.  Each ErrorMessage
     *          represents one processing error.
     **/
    public ProcessingException(ErrorList e) {
        errs = e;
    }

    /**
     * Constructor for ProcessingException.
     *
     * @param e   An ErrorList which is an ArrayList of ErrorMessage objects.  Each ErrorMessage
     *            represents one processing error.
     * @param aee AddressErrorException object containing specialized error message, cause, address
     **/
    public ProcessingException(ErrorList e, AddressErrorException aee) {
        errs = e;
        Exceptions.setRegisters(aee.getType(), aee.getAddress());
    }

    /**
     * Constructor for ProcessingException to handle runtime exceptions
     *
     * @param ps a ProgramStatement of statement causing runtime exception
     * @param m  a String containing specialized error message
     **/
    public ProcessingException(ProgramStatement ps, String m) {
        errs = new ErrorList();
        errs.add(new ErrorMessage(ps, "Runtime exception at " +
                Binary.intToHexString(RegisterFile.getProgramCounter() - Instruction.INSTRUCTION_LENGTH) +
                ": " + m));
        // Stopped using ps.getAddress() because of pseudo-instructions.  All instructions in
        // the macro expansion point to the same ProgramStatement, and thus all will return the
        // same value for getAddress(). But only the first such expanded instruction will 
        // be stored at that address.  So now I use the program counter (which has already
        // been incremented).
    }


    /**
     * Constructor for ProcessingException to handle runtime exceptions
     *
     * @param ps    a ProgramStatement of statement causing runtime exception
     * @param m     a String containing specialized error message
     * @param cause exception cause (see Exceptions class for list)
     **/
    public ProcessingException(ProgramStatement ps, String m, int cause) {
        this(ps, m);
        Exceptions.setRegisters(cause);
    }


    /**
     * Constructor for ProcessingException to handle address runtime exceptions
     *
     * @param ps  a ProgramStatement of statement causing runtime exception
     * @param aee AddressErrorException object containing specialized error message, cause, address
     **/

    public ProcessingException(ProgramStatement ps, AddressErrorException aee) {
        this(ps, aee.getMessage());
        Exceptions.setRegisters(aee.getType(), aee.getAddress());
    }

    /**
     * Constructor for ProcessingException.
     * <p>
     * No parameter and thus no error list.  Use this for normal MIPS
     * program termination (e.g. syscall 10 for exit).
     **/
    public ProcessingException() {
        errs = null;
    }

    /**
     * Produce the list of error messages.
     *
     * @return Returns ErrorList of error messages.
     * @see ErrorList
     * @see ErrorMessage
     **/

    public ErrorList errors() {
        return errs;
    }

}
