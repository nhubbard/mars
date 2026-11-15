package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.SimulationCode
import edu.missouristate.mars.simulator.Exceptions.SYSCALL_EXCEPTION

private val dispatchSyscall = SimulationCode { statement, context ->
    val number = context.registerFile.getValue(2)
    val service = context.syscalls().findByNumber(number)
        ?: throw ProcessingException(context, statement, "invalid or unimplemented syscall service: $number ", SYSCALL_EXCEPTION)
    if (!context.environment().allowsSyscall(number)) {
        throw ProcessingException(context, statement, "syscall $number is disabled by the host", SYSCALL_EXCEPTION)
    }
    service.simulate(statement, context)
}

open class InstrSyscall : BasicInstruction(
    "syscall",
    $$"Issue a system call : Execute the system call specified by value in $v0",
    BasicInstructionFormat.R_FORMAT,
    "000000 ccccc ccccc ccccc ccccc 001100",
    dispatchSyscall,
)

internal class InstrCodedSyscall : BasicInstruction(
    "syscall 1048575",
    $$"Issue a system call with a 20-bit software parameter. The service number is in $v0.",
    BasicInstructionFormat.R_FORMAT,
    "000000 ffffffffffffffffffff 001100",
    dispatchSyscall,
) {
    override fun acceptsEncoding(word: Int): Boolean = word != 0x0000000c

    override fun encodeOperands(values: IntArray): IntArray {
        require(values[0] in 0..0xfffff) { "syscall code must be an unsigned 20-bit value" }
        return values
    }
}
