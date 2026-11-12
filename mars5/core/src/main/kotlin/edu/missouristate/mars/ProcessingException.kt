package edu.missouristate.mars

import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.VADDR
import edu.missouristate.mars.mips.instructions.Instruction.Companion.INSTRUCTION_LENGTH
import edu.missouristate.mars.util.Binary

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

/** An assembly or execution failure. An absent error list can signal normal program termination. */
open class ProcessingException private constructor(
    private val diagnostics: ErrorList?,
    private val origin: Origin?,
    message: String?,
) : Exception(message) {
    constructor(errors: ErrorList?) : this(errors, null, null)

    @Suppress("UNUSED_PARAMETER")
    constructor(errors: ErrorList?, addressError: AddressErrorException?) : this(errors)

    constructor() : this(null)

    constructor(statement: ProgramStatement, message: String?) : this(ErrorList(), statement, statement.address, message)

    @Suppress("UNUSED_PARAMETER")
    constructor(statement: ProgramStatement, message: String?, cause: Int) : this(statement, message)

    constructor(statement: ProgramStatement, error: AddressErrorException) : this(statement, error.message)

    constructor(context: InstructionContext, statement: ProgramStatement?, message: String?) :
        this(context.newErrorList(), statement, context.registerFile.programCounter - INSTRUCTION_LENGTH, message)

    private constructor(errors: ErrorList, statement: ProgramStatement?, address: Int, message: String?) :
        this(errors, statement!!.instructionOrigin, "Runtime exception at ${Binary.intToHexString(address)}: $message") {
        errors.add(ErrorMessage(statement, this.message))
    }

    constructor(context: InstructionContext, statement: ProgramStatement?, message: String?, cause: Int) :
        this(context, statement, message) {
        context.setExceptionRegisters(cause)
    }

    constructor(context: InstructionContext, statement: ProgramStatement?, error: AddressErrorException) :
        this(context, statement, error.message) {
        context.updateCoprocessor0(VADDR, error.address)
        context.setExceptionRegisters(error.type)
    }

    open fun instructionOrigin(): Origin? = origin

    open fun errors(): ErrorList? = diagnostics
}
