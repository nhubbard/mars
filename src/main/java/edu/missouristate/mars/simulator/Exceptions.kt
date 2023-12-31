/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package edu.missouristate.mars.simulator

import edu.missouristate.mars.mips.hardware.Coprocessor0
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.util.Binary

/**
 * Represents an error/interrupt that occurs during execution (simulation).
 *
 * @author Pete Sanderson
 * @version August 2005
 */
enum class Exceptions(val rawValue: Int) {
    GENERIC_EXCEPTION(0),
    EXTERNAL_INTERRUPT_KEYBOARD(0x00000040),
    EXTERNAL_INTERRUPT_DISPLAY(0x00000080),
    ADDRESS_EXCEPTION_LOAD(4),
    ADDRESS_EXCEPTION_STORE(5),
    SYSCALL_EXCEPTION(8),
    BREAKPOINT_EXCEPTION(9),
    RESERVED_INSTRUCTION_EXCEPTION(10),
    ARITHMETIC_OVERFLOW_EXCEPTION(12),
    TRAP_EXCEPTION(13),
    DIVIDE_BY_ZERO_EXCEPTION(15),
    FLOATING_POINT_OVERFLOW(16),
    FLOATING_POINT_UNDERFLOW(17);

    companion object {
        /**
         * Given MIPS exception cause code, will place that code into
         * coprocessor 0 CAUSE register ($13), set the EPC register to
         * "current" program counter, and set Exception Level bit in STATUS register.
         *
         * @param cause The cause code (see Exceptions for a list)
         */
        @JvmStatic
        fun setRegisters(cause: Exceptions) {
            // Set CAUSE register bits 2 through 6 to cause value. The "& 0xFFFFFC83" will set bits 2-6 and 8-9 to 0
            // while keeping all the others.  Left-shift by 2 to put cause value into position then OR it in.  Bits 8-9
            // are used to identify devices for External Interrupt (8=keyboard,9=display).
            Coprocessor0.updateRegister(
                Coprocessor0.CAUSE,
                (Coprocessor0.getValue(Coprocessor0.CAUSE) and 0xFFFFFC83.toInt() or (cause.rawValue shl 2))
            )
            // When exception occurred, PC had already been incremented so need to subtract 4 here.
            Coprocessor0.updateRegister(
                Coprocessor0.EPC,
                RegisterFile.programCounter.getValue() - Instruction.INSTRUCTION_LENGTH
            )
            // Set EXL (exception level) bit in position 1 in STATUS register to 1.
            Coprocessor0.updateRegister(
                Coprocessor0.STATUS,
                Binary.setBit(Coprocessor0.getValue(Coprocessor0.STATUS), Coprocessor0.EXCEPTION_LEVEL)
            )
        }

        /**
         * Given MIPS exception cause code and bad address, place the bad address into VADDR
         * register ($8) then call overloaded setRegisters with the cause code to do the rest.
         *
         * @param cause The cause code (see Exceptions for a list). Should be address exception.
         * @param addr  The address that caused the exception.
         */
        @JvmStatic
        fun setRegisters(cause: Exceptions, addr: Int) {
            Coprocessor0.updateRegister(Coprocessor0.VADDR, addr)
            setRegisters(cause)
        }

        @JvmStatic
        fun fromInt(rawValue: Int): Exceptions = entries.firstOrNull { it.rawValue == rawValue } ?:
            throw IllegalArgumentException("No Exceptions entry with raw value $rawValue.")
    }
}