package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.util.Binary;

/**
 * Represents MIPS AddressErrorException. This is generated by the assembler when the
 * source code references a memory address not valid for the context.
 *
 * @author Pete Sanderson
 * @version August 2003
 **/
public class AddressErrorException extends Exception {
    private int address;
    private int type;  // Exceptions.ADDRESS_EXCEPTION_LOAD,Exceptions.ADDRESS_EXCEPTION_STORE


    /**
     * Constructor for the AddressErrorException class
     *
     * @param addr The erroneous memory address.
     **/

    public AddressErrorException(String message, int exceptType, int addr) {
        super(message + Binary.intToHexString(addr));
        address = addr;
        type = exceptType;
    }

    /**
     * Get the erroneous memory address.
     *
     * @return The erroneous memory address.
     **/
    public int getAddress() {
        return address;
    }

    /**
     * Get the exception type (load or store).
     *
     * @return Exception type: Exceptions.ADDRESS_EXCEPTION_LOAD, Exceptions.ADDRESS_EXCEPTION_STORE
     **/
    public int getType() {
        return type;
    }
}
