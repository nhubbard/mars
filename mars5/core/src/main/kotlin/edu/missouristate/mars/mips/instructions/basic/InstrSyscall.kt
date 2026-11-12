package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.SYSCALL_EXCEPTION

open class InstrSyscall : BasicInstruction(
    "syscall",
    "Issue a system call : Execute the system call specified by value in \$v0",
    BasicInstructionFormat.R_FORMAT,
    "000000 ccccc ccccc ccccc ccccc 001100",
    { statement, context ->
        val number = context.registerFile.getValue(2)
        val service = context.syscalls().findByNumber(number)
            ?: throw ProcessingException(context, statement, "invalid or unimplemented syscall service: $number ", SYSCALL_EXCEPTION)
        service.simulate(statement, context)
    },
)
