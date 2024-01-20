/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars

import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.util.Binary

/**
 * Class to represent error that occurs while assembling or running a MIPS program.
 *
 * @author Pete Sanderson
 * @version August 2003
 */
class ProcessingException : Exception {
    private val errs: ErrorList?

    /**
     * Constructor for ProcessingException.
     *
     * @param e An ErrorList which is an ArrayList of ErrorMessage objects.  Each ErrorMessage
     * represents one processing error.
     */
    constructor(e: ErrorList?) {
        errs = e
    }

    /**
     * Constructor for ProcessingException.
     *
     * @param e   An ErrorList which is an ArrayList of ErrorMessage objects.  Each ErrorMessage
     * represents one processing error.
     * @param aee AddressErrorException object containing the specialized error message, cause, address
     */
    constructor(e: ErrorList?, aee: AddressErrorException) {
        errs = e
        Exceptions.setRegisters(aee.type, aee.address)
    }

    /**
     * Constructor for ProcessingException to handle runtime exceptions
     *
     * @param ps a ProgramStatement of statement causing runtime exception
     * @param m  a String containing specialized error message
     */
    constructor(ps: ProgramStatement?, m: String?) {
        errs = ErrorList()
        errs.add(
            ErrorMessage(
                ps!!, "Runtime exception at " +
                        Binary.intToHexString(RegisterFile.programCounter.getValue() - Instruction.INSTRUCTION_LENGTH) +
                        ": " + m
            )
        )
        // Stopped using ps.getAddress() because of pseudo-instructions.  All instructions in
        // the macro expansion point to the same ProgramStatement, and thus all will return the
        // same value for getAddress(). But only the first such expanded instruction will 
        // be stored at that address.  So now I use the program counter (which has already
        // been incremented).
    }

    /**
     * Constructor for ProcessingException to handle runtime exceptions
     *
     * @param ps    a ProgramStatement of statement causing runtime exception
     * @param m     a String containing specialized error message
     * @param cause exception cause (see Exceptions class for list)
     */
    constructor(ps: ProgramStatement?, m: String?, cause: Exceptions) : this(ps, m) {
        Exceptions.setRegisters(cause)
    }

    /**
     * Constructor for ProcessingException to handle address runtime exceptions
     *
     * @param ps  a ProgramStatement of statement causing runtime exception
     * @param aee AddressErrorException object containing specialized error message, cause, address
     */
    constructor(ps: ProgramStatement?, aee: AddressErrorException) : this(ps, aee.message) {
        Exceptions.setRegisters(aee.type, aee.address)
    }

    /**
     * Constructor for ProcessingException.
     *
     *
     * No parameter and thus no error list.  Use this for normal MIPS
     * program termination (e.g. syscall 10 for exit).
     */
    constructor() {
        errs = null
    }

    /**
     * Produce the list of error messages.
     *
     * @return Returns ErrorList of error messages.
     * @see ErrorList
     *
     * @see ErrorMessage
     */
    fun errors(): ErrorList? = errs
}
