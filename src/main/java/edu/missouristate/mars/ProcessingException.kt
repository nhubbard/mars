package edu.missouristate.mars

import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.util.Binary

/**
 * Class to represent error that occurs while assembling or running a MIPS program.
 *
 * @author Pete Sanderson
 * @version August 2003
 */
class ProcessingException : Exception {
    private val errs: ErrorList?

    /**
     * Constructor for ProcessingException.
     *
     * @param e An ErrorList which is an ArrayList of ErrorMessage objects.  Each ErrorMessage
     * represents one processing error.
     */
    constructor(e: ErrorList?) {
        errs = e
    }

    /**
     * Constructor for ProcessingException.
     *
     * @param e   An ErrorList which is an ArrayList of ErrorMessage objects.  Each ErrorMessage
     * represents one processing error.
     * @param aee AddressErrorException object containing specialized error message, cause, address
     */
    constructor(e: ErrorList?, aee: AddressErrorException) {
        errs = e
        Exceptions.setRegisters(aee.type, aee.address)
    }

    /**
     * Constructor for ProcessingException to handle runtime exceptions
     *
     * @param ps a ProgramStatement of statement causing runtime exception
     * @param m  a String containing specialized error message
     */
    constructor(ps: ProgramStatement?, m: String?) {
        errs = ErrorList()
        errs.add(
            ErrorMessage(
                ps!!, "Runtime exception at " +
                        Binary.intToHexString(RegisterFile.programCounter.getValue() - Instruction.INSTRUCTION_LENGTH) +
                        ": " + m
            )
        )
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
     */
    constructor(ps: ProgramStatement?, m: String?, cause: Exceptions) : this(ps, m) {
        Exceptions.setRegisters(cause)
    }

    /**
     * Constructor for ProcessingException to handle address runtime exceptions
     *
     * @param ps  a ProgramStatement of statement causing runtime exception
     * @param aee AddressErrorException object containing specialized error message, cause, address
     */
    constructor(ps: ProgramStatement?, aee: AddressErrorException) : this(ps, aee.message) {
        Exceptions.setRegisters(aee.type, aee.address)
    }

    /**
     * Constructor for ProcessingException.
     *
     *
     * No parameter and thus no error list.  Use this for normal MIPS
     * program termination (e.g. syscall 10 for exit).
     */
    constructor() {
        errs = null
    }

    /**
     * Produce the list of error messages.
     *
     * @return Returns ErrorList of error messages.
     * @see ErrorList
     *
     * @see ErrorMessage
     */
    fun errors(): ErrorList? = errs
}
