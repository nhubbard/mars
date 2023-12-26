package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.ErrorList;

/**
 * Represents attempt to access double precision register using an odd
 * (e.g. $f1, $f23) register name.
 *
 * @author Pete Sanderson
 * @version July 2005
 **/

public class InvalidRegisterAccessException extends Exception {
    private ErrorList errs;

    /**
     * Constructor for IllegalRegisterException.
     **/
    public InvalidRegisterAccessException() {
    }

}
   