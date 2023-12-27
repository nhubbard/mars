package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement

/**
 * Abstract class that a MIPS syscall system service may extend.  A qualifying service
 * must be a class in the `edu.missouristate.mars.mips.instructions.syscalls` package that
 * implements the Syscall interface, must be compiled into a .class file,
 * and its .class file must be in the same folder as Syscall.class.
 * Mars will detect a qualifying syscall upon startup, create an instance
 * using its no-argument constructor and add it to its syscall list.
 * When its service is invoked at runtime ("syscall" instruction
 * with its service number stored in register $v0), its simulate()
 * method will be invoked.
 *
 * Constructor is provided so subclass may initialize instance variables.
 *
 * @param number default assigned service number
 * @param name   service name which may be used for reference independent of number
 */
abstract class AbstractSyscall(
    override var number: Int,
    override val name: String
) : Syscall {
    /**
     * Performs syscall function.
     * It will be invoked when the service is invoked at simulation time.
     * The service is identified by value stored in $v0.
     *
     * @param statement ProgramStatement object for this syscall instruction.
     */
    @Throws(ProcessingException::class)
    abstract override fun simulate(statement: ProgramStatement)
}