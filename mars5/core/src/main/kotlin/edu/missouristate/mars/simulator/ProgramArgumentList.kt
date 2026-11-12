package edu.missouristate.mars.simulator

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState
import java.util.StringTokenizer

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

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

/** Initializes the simulated program's argc and argv from a copy of its launch arguments. */
open class ProgramArgumentList {
    private val arguments: ArrayList<String>

    /** Splits on space, tab, newline, carriage return, and form feed. */
    constructor(args: String) {
        val tokens = StringTokenizer(args)
        arguments = ArrayList(tokens.countTokens())
        while (tokens.hasMoreTokens()) {
            arguments.add(tokens.nextToken())
        }
    }

    constructor(list: Array<out String>) : this(list, 0)

    constructor(list: Array<out String>, startPosition: Int) {
        arguments = ArrayList(list.size - startPosition)
        arguments.addAll(list.asList().subList(startPosition, list.size))
    }

    constructor(list: ArrayList<String>?) : this(list, 0)

    constructor(list: ArrayList<String>?, startPosition: Int) {
        arguments = if (list == null || list.size < startPosition) {
            ArrayList(0)
        } else {
            ArrayList(list.subList(startPosition, list.size))
        }
    }

    open fun storeProgramArguments(session: MarsContext) = storeProgramArguments(session) {}

    /** Initializes the argument frame before execution and checks cancellation during setup. */
    open fun storeProgramArguments(session: MarsContext, checkCancellation: Runnable) {
        if (arguments.isEmpty()) return

        val memory = session.memory
        // Store the strings from the top of the stack downward and reserve space below them
        // for the null sentinel, each argv pointer, and argc before writing anything.
        var freeBelowStrings = memory.stackBaseAddress.toLong()
        for (argument in arguments) {
            checkCancellation.run()
            freeBelowStrings -= argument.length.toLong() + 1
        }
        val word = MemoryState.WORD_LENGTH_BYTES
        var sentinelAddress = memory.stackPointer.toLong()
        if (freeBelowStrings < sentinelAddress + word - 1) {
            sentinelAddress = Math.floorDiv(freeBelowStrings, word) * word - word
        }
        val argcAddress = sentinelAddress - (arguments.size.toLong() + 1) * word
        require(argcAddress > memory.stackLimitAddress) { "Program arguments exceed session stack memory" }

        var highAddress = memory.stackBaseAddress
        val argumentAddresses = IntArray(arguments.size)
        try {
            for ((index, argument) in arguments.withIndex()) {
                checkCancellation.run()
                memory.set(highAddress--, 0, 1)
                for (characterIndex in argument.indices.reversed()) {
                    if ((characterIndex and 0xff) == 0) {
                        checkCancellation.run()
                    }
                    memory.set(highAddress--, argument[characterIndex].code, 1)
                }
                argumentAddresses[index] = highAddress + 1
            }

            var stackAddress = sentinelAddress.toInt()
            memory.set(stackAddress, 0, word)
            stackAddress -= word
            for (index in argumentAddresses.indices.reversed()) {
                checkCancellation.run()
                memory.set(stackAddress, argumentAddresses[index], word)
                stackAddress -= word
            }
            memory.set(stackAddress, argumentAddresses.size, word)

            // Initialize the registers directly to keep launch setup out of the undo history.
            session.registerFile.getUserRegister("\$sp")!!.setValue(stackAddress)
            session.registerFile.getUserRegister("\$a0")!!.setValue(argumentAddresses.size)
            session.registerFile.getUserRegister("\$a1")!!.setValue(stackAddress + word)
        } catch (failure: AddressErrorException) {
            throw IllegalArgumentException("Program arguments exceed session stack memory", failure)
        }
    }
}
