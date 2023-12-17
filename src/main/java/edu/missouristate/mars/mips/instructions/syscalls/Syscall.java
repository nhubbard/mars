package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;


/**
 * Interface for any MIPS syscall system service.  A qualifying service
 * must be a class in the mars.mips.instructions.syscalls package that
 * implements the Syscall interface, must be compiled into a .class file,
 * and its .class file must be in the same folder as Syscall.class.
 * Mars will detect a qualifying syscall upon startup, create an instance
 * using its no-argument constructor and add it to its syscall list.
 * When its service is invoked at runtime ("syscall" instruction
 * with its service number stored in register $v0), its simulate()
 * method will be invoked.
 */

public interface Syscall {
    /**
     * Return a name you have chosen for this syscall.  This can be used by a MARS
     * user to refer to the service when choosing to override its default service
     * number in the configuration file.
     *
     * @return service name as a string
     */
    public abstract String getName();

    /**
     * Set the service number.  This is provided to allow MARS implementer or user
     * to override the default service number.
     *
     * @param num specified service number to override the default.
     */
    public abstract void setNumber(int num);

    /**
     * Return the assigned service number.  This is the number the MIPS programmer
     * must store into $v0 before issuing the SYSCALL instruction.
     *
     * @return assigned service number
     */
    public abstract int getNumber();

    /**
     * Performs syscall function.  It will be invoked when the service is invoked
     * at simulation time.  Service is identified by value stored in $v0.
     *
     * @param statement ProgramStatement for this syscall statement.
     */
    public abstract void simulate(ProgramStatement statement)
            throws ProcessingException;
}