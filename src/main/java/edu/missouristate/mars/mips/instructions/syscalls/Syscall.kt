package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement

/**
 * Interface for any MIPS syscall system service.  A qualifying service
 * must be a class in the edu.missouristate.mars.mips.instructions.syscalls package that
 * implements the Syscall interface, must be compiled into a .class file,
 * and its .class file must be in the same folder as Syscall.class.
 * Mars will detect a qualifying syscall upon startup, create an instance
 * using its no-argument constructor and add it to its syscall list.
 * When its service is invoked at runtime ("syscall" instruction
 * with its service number stored in register $v0), its simulate()
 * method will be invoked.
 */
interface Syscall {
    /**
     * Return a name you have chosen for this syscall.  This can be used by a MARS
     * user to refer to the service when choosing to override its default service
     * number in the configuration file.
     *
     * @return service name as a string
     */
    val name: String?

    /**
     * The assigned service number. This is the number the MIPS programmer
     * must store into $v0 before issuing the SYSCALL instruction.
     *
     * @return assigned service number
     */
    var number: Int

    /**
     * Performs syscall function.
     * It will be invoked when the service is invoked at simulation time.
     * The service is identified by value stored in $v0.
     *
     * @param statement ProgramStatement for this syscall statement.
     */
    @Throws(ProcessingException::class)
    fun simulate(statement: ProgramStatement)
}