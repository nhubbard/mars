package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.simulator.Exceptions;
import edu.missouristate.mars.util.Binary;
import edu.missouristate.mars.util.SystemIO;

/**
 * Service to read the bits of console input double into $f0 and $f1.
 * $f1 contains high order word of the double.
 */

public class SyscallReadDouble extends AbstractSyscall {
    /**
     * Build an instance of the Read Double syscall.  Default service number
     * is 7 and name is "ReadDouble".
     */
    public SyscallReadDouble() {
        super(7, "ReadDouble");
    }

    /**
     * Performs syscall function to read the bits of input double into $f0 and $f1.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        //  Higher numbered reg contains high order word so order is $f1 - $f0.
        double doubleValue;
        try {
            doubleValue = SystemIO.readDouble(this.getNumber());
        } catch (NumberFormatException e) {
            throw new ProcessingException(statement,
                    "invalid double input (syscall " + this.getNumber() + ")",
                    Exceptions.SYSCALL_EXCEPTION);
        }
        long longValue = Double.doubleToRawLongBits(doubleValue);
        Coprocessor1.updateRegister(1, Binary.highOrderLongToInt(longValue));
        Coprocessor1.updateRegister(0, Binary.lowOrderLongToInt(longValue));
    }
}