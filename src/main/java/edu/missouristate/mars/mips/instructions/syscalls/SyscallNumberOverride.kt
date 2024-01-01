/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
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

package edu.missouristate.mars.mips.instructions.syscalls

import kotlin.system.exitProcess

/**
 * Represents User override of default syscall number assignment.
 * Such overrides are specified in the config.txt file read when
 * MARS starts up.
 */
class SyscallNumberOverride(val name: String, value: String) {
    /**
     * Get the new service number as an int.
     *
     * @return the service number
     */
    var number: Int = 0

    /**
     * Constructor is called with two strings: service name and desired
     * number.
     * Will throw an exception if the number is malformed, but does
     * not check the validity of the service name or number.
     *
     * @param serviceName a String containing syscall service mnemonic.
     * @param value       a String containing its reassigned syscall service number.
     * If this number is previously assigned to a different syscall which does not
     * also receive a new number, then an error for duplicate numbers will
     * be issued at MARS launch.
     */
    init {
        try {
            this.number = value.trim { it <= ' ' }.toInt()
        } catch (e: NumberFormatException) {
            println("Error processing Syscall number override: '${value.trim { it <= ' ' }}' is not a valid integer")
            exitProcess(0)
        }
    }
}



