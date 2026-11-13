package edu.missouristate.mars.simulator

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** MIPS exception numbers used to populate the coprocessor 0 Cause register. */
object Exceptions {
    // Keyboard and display share the external-interrupt exception number (zero).
    // These values become the pending bits in positions 8 and 9 when shifted left by two.
    const val EXTERNAL_INTERRUPT_KEYBOARD = 0x00000040
    const val EXTERNAL_INTERRUPT_DISPLAY = 0x00000080
    const val ADDRESS_EXCEPTION_LOAD = 4
    const val ADDRESS_EXCEPTION_STORE = 5
    const val SYSCALL_EXCEPTION = 8
    const val BREAKPOINT_EXCEPTION = 9
    const val RESERVED_INSTRUCTION_EXCEPTION = 10
    const val ARITHMETIC_OVERFLOW_EXCEPTION = 12
    const val TRAP_EXCEPTION = 13
}
