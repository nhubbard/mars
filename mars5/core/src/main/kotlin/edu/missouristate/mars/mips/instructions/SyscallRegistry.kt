package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.mips.instructions.syscalls.Syscall
import edu.missouristate.mars.mips.instructions.syscalls.SyscallClearScreen
import edu.missouristate.mars.mips.instructions.syscalls.SyscallClose
import edu.missouristate.mars.mips.instructions.syscalls.SyscallConfirmDialog
import edu.missouristate.mars.mips.instructions.syscalls.SyscallExit
import edu.missouristate.mars.mips.instructions.syscalls.SyscallExit2
import edu.missouristate.mars.mips.instructions.syscalls.SyscallInputDialogDouble
import edu.missouristate.mars.mips.instructions.syscalls.SyscallInputDialogFloat
import edu.missouristate.mars.mips.instructions.syscalls.SyscallInputDialogInt
import edu.missouristate.mars.mips.instructions.syscalls.SyscallInputDialogString
import edu.missouristate.mars.mips.instructions.syscalls.SyscallMessageDialog
import edu.missouristate.mars.mips.instructions.syscalls.SyscallMessageDialogDouble
import edu.missouristate.mars.mips.instructions.syscalls.SyscallMessageDialogFloat
import edu.missouristate.mars.mips.instructions.syscalls.SyscallMessageDialogInt
import edu.missouristate.mars.mips.instructions.syscalls.SyscallMessageDialogString
import edu.missouristate.mars.mips.instructions.syscalls.SyscallMidiOut
import edu.missouristate.mars.mips.instructions.syscalls.SyscallMidiOutSync
import edu.missouristate.mars.mips.instructions.syscalls.SyscallOpen
import edu.missouristate.mars.mips.instructions.syscalls.SyscallPrintChar
import edu.missouristate.mars.mips.instructions.syscalls.SyscallPrintDouble
import edu.missouristate.mars.mips.instructions.syscalls.SyscallPrintFloat
import edu.missouristate.mars.mips.instructions.syscalls.SyscallPrintInt
import edu.missouristate.mars.mips.instructions.syscalls.SyscallPrintIntBinary
import edu.missouristate.mars.mips.instructions.syscalls.SyscallPrintIntHex
import edu.missouristate.mars.mips.instructions.syscalls.SyscallPrintIntUnsigned
import edu.missouristate.mars.mips.instructions.syscalls.SyscallPrintString
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRandDouble
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRandFloat
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRandInt
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRandIntRange
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRandSeed
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRead
import edu.missouristate.mars.mips.instructions.syscalls.SyscallReadChar
import edu.missouristate.mars.mips.instructions.syscalls.SyscallReadDouble
import edu.missouristate.mars.mips.instructions.syscalls.SyscallReadFloat
import edu.missouristate.mars.mips.instructions.syscalls.SyscallReadInt
import edu.missouristate.mars.mips.instructions.syscalls.SyscallReadString
import edu.missouristate.mars.mips.instructions.syscalls.SyscallSbrk
import edu.missouristate.mars.mips.instructions.syscalls.SyscallSleep
import edu.missouristate.mars.mips.instructions.syscalls.SyscallTime
import edu.missouristate.mars.mips.instructions.syscalls.SyscallWrite
import java.util.Map.entry
import java.util.Map.ofEntries

/** Session-owned MIPS syscall implementations keyed by their dispatch numbers. */
class SyscallRegistry {
    private val byNumber = ofEntries<Int, Syscall>(
        entry(1, SyscallPrintInt()),
        entry(2, SyscallPrintFloat()),
        entry(3, SyscallPrintDouble()),
        entry(4, SyscallPrintString()),
        entry(5, SyscallReadInt()),
        entry(6, SyscallReadFloat()),
        entry(7, SyscallReadDouble()),
        entry(8, SyscallReadString()),
        entry(9, SyscallSbrk()),
        entry(10, SyscallExit()),
        entry(11, SyscallPrintChar()),
        entry(12, SyscallReadChar()),
        entry(13, SyscallOpen()),
        entry(14, SyscallRead()),
        entry(15, SyscallWrite()),
        entry(16, SyscallClose()),
        entry(17, SyscallExit2()),
        entry(30, SyscallTime()),
        entry(31, SyscallMidiOut()),
        entry(32, SyscallSleep()),
        entry(33, SyscallMidiOutSync()),
        entry(34, SyscallPrintIntHex()),
        entry(35, SyscallPrintIntBinary()),
        entry(36, SyscallPrintIntUnsigned()),
        entry(40, SyscallRandSeed()),
        entry(41, SyscallRandInt()),
        entry(42, SyscallRandIntRange()),
        entry(43, SyscallRandFloat()),
        entry(44, SyscallRandDouble()),
        entry(50, SyscallConfirmDialog()),
        entry(51, SyscallInputDialogInt()),
        entry(52, SyscallInputDialogFloat()),
        entry(53, SyscallInputDialogDouble()),
        entry(54, SyscallInputDialogString()),
        entry(55, SyscallMessageDialog()),
        entry(56, SyscallMessageDialogInt()),
        entry(57, SyscallMessageDialogFloat()),
        entry(58, SyscallMessageDialogDouble()),
        entry(59, SyscallMessageDialogString()),
        entry(60, SyscallClearScreen()),
    )

    fun findByNumber(number: Int): Syscall? = byNumber[number]

    fun services(): Collection<Syscall> = byNumber.values
}
