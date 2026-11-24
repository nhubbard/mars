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
import java.util.Collections.unmodifiableCollection

/** Session-owned MIPS syscall implementations keyed by their dispatch numbers. */
class SyscallRegistry {
  private val byNumber =
      mapOf<Int, Syscall>(
          1 to SyscallPrintInt(),
          2 to SyscallPrintFloat(),
          3 to SyscallPrintDouble(),
          4 to SyscallPrintString(),
          5 to SyscallReadInt(),
          6 to SyscallReadFloat(),
          7 to SyscallReadDouble(),
          8 to SyscallReadString(),
          9 to SyscallSbrk(),
          10 to SyscallExit(),
          11 to SyscallPrintChar(),
          12 to SyscallReadChar(),
          13 to SyscallOpen(),
          14 to SyscallRead(),
          15 to SyscallWrite(),
          16 to SyscallClose(),
          17 to SyscallExit2(),
          30 to SyscallTime(),
          31 to SyscallMidiOut(),
          32 to SyscallSleep(),
          33 to SyscallMidiOutSync(),
          34 to SyscallPrintIntHex(),
          35 to SyscallPrintIntBinary(),
          36 to SyscallPrintIntUnsigned(),
          40 to SyscallRandSeed(),
          41 to SyscallRandInt(),
          42 to SyscallRandIntRange(),
          43 to SyscallRandFloat(),
          44 to SyscallRandDouble(),
          50 to SyscallConfirmDialog(),
          51 to SyscallInputDialogInt(),
          52 to SyscallInputDialogFloat(),
          53 to SyscallInputDialogDouble(),
          54 to SyscallInputDialogString(),
          55 to SyscallMessageDialog(),
          56 to SyscallMessageDialogInt(),
          57 to SyscallMessageDialogFloat(),
          58 to SyscallMessageDialogDouble(),
          59 to SyscallMessageDialogString(),
          60 to SyscallClearScreen(),
      )

  private val services = unmodifiableCollection(byNumber.values)

  fun findByNumber(number: Int): Syscall? = byNumber[number]

  fun services(): Collection<Syscall> = services
}
