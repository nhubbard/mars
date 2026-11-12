package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.core.InstructionContext

open class SyscallClearScreen : AbstractSyscall(60, "ClearScreen") {
    override fun simulate(statement: ProgramStatement?, context: InstructionContext) {
        context.environment().clearConsole()
    }
}
