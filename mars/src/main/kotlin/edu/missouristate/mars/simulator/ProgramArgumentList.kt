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

package edu.missouristate.mars.simulator

import edu.missouristate.mars.Globals
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.hardware.Memory.Companion.stackPointer
import edu.missouristate.mars.mips.hardware.RegisterFile.getUserRegister
import java.util.*
import kotlin.system.exitProcess

/**
 * Models Program Arguments, one or more strings provided to the MIPS
 * program at runtime. Equivalent to C's `main(int argc, char **argv)` or
 * Java's `main(String[] args)`.
 *
 * @author Pete Sanderson
 * @version July 2008
 */
@Suppress("MemberVisibilityCanBePrivate")
class ProgramArgumentList {
    val programArgumentList: ArrayList<String>

    /**
     * Constructor that parses a string to produce a list. Delimiters are the default Java StringTokenizer delimiters
     * (space, tab, newLine, return, form feed).
     *
     * @param args The String containing the delimiter-separated arguments.
     */
    constructor(args: String) {
        val st = StringTokenizer(args)
        programArgumentList = ArrayList(st.countTokens())
        while (st.hasMoreTokens()) programArgumentList.add(st.nextToken())
    }

    /**
     * Constructor that gets list from the section of String array, one
     * argument per element.
     *
     * @param list          Array of String, each element containing one argument
     * @param startPosition Index of the array element containing the first argument; all remaining
     *                      elements are assumed to contain an argument.
     */
    @JvmOverloads
    constructor(list: Array<String>, startPosition: Int = 0) {
        programArgumentList = ArrayList(list.size - startPosition)
        programArgumentList.addAll(list.toList().subList(startPosition, list.size))
    }
    /**
     * Constructor that gets list from the section of String ArrayList, one
     * argument per element.
     *
     * @param list          ArrayList of String, each element containing one argument
     * @param startPosition Index of the array element containing the first argument; all remaining
     *                      elements are assumed to contain an argument.
     */
    @JvmOverloads
    constructor(list: ArrayList<String>, startPosition: Int = 0) {
        if (list.size < startPosition) {
            programArgumentList = arrayListOf()
        } else {
            programArgumentList = ArrayList(list.size - startPosition)
            for (i in startPosition..<list.size) programArgumentList.add(list[i])
        }
    }

    // Place any program arguments into MIPS memory and registers
    // Arguments are stored starting at the highest word of non-kernel
    // memory and working back toward runtime stack (there is a
    // 4096-byte gap in between).  The argument count (argc) and pointers
    // to the arguments are stored on the runtime stack.  The stack
    // pointer register $sp is adjusted accordingly and $a0 is set
    // to the argument count (argc), and $a1 is set to the stack
    // address holding the first argument pointer (argv).
    fun storeProgramArguments() {
        if (programArgumentList.isEmpty()) return
        // Runtime stack initialization from stack top-down (each is 4 bytes) :
        //    programArgumentList.size()
        //    address of first character of first program argument
        //    address of first character of second program argument
        //    ....repeat for all program arguments
        //    0x00000000    (null terminator for list of string pointers)
        // $sp will be set to the address holding the arg list size
        // $a0 will be set to the arg list size (argc)
        // $a1 will be set to stack address just "below" arg list size (argv)
        //
        // Each of the arguments themselves will be stored starting at
        // Memory.stackBaseAddress (0x7ffffffc) and working down from there:
        // 0x7ffffffc will contain null terminator for first arg
        // 0x7ffffffb will contain last character of first arg
        // 0x7ffffffa will contain next-to-last character of first arg
        // Etc down to first character of first arg.
        // Previous address will contain null terminator for second arg
        // Previous-to-that contains last character of second arg
        // Etc down to first character of second arg.
        // Follow this pattern for all remaining arguments.
        var highAddress = Memory.stackBaseAddress
        var programArgument: String
        val argStartAddress = IntArray(programArgumentList.size)
        try { // needed for all memory writes
            for (i in programArgumentList.indices) {
                programArgument = programArgumentList[i]
                Globals.memory.set(highAddress, 0, 1) // trailing null byte for each argument
                highAddress--
                for (j in programArgument.length - 1 downTo 0) {
                    Globals.memory.set(highAddress, programArgument[j].code, 1)
                    highAddress--
                }
                argStartAddress[i] = highAddress + 1
            }
            // now place a null word, the arg starting addresses, and arg count onto stack.
            var stackAddress = stackPointer // base address for runtime stack.
            if (highAddress < stackPointer) {
                // Based on current values for stackBaseAddress and stackPointer, this will
                // only happen if the combined length of program arguments is greater than
                // 0x7ffffffc - 0x7fffeffc = 0x00001000 = 4096 bytes.  In this case, set
                // stackAddress to next lower word boundary minus 4 for clearance (since every
                // byte from highAddress+1 is filled).
                stackAddress = highAddress - (highAddress % Memory.WORD_LENGTH_BYTES) - Memory.WORD_LENGTH_BYTES
            }
            Globals.memory.set(stackAddress, 0, Memory.WORD_LENGTH_BYTES) // null word for the end of argv array
            stackAddress -= Memory.WORD_LENGTH_BYTES
            for (i in argStartAddress.indices.reversed()) {
                Globals.memory.set(stackAddress, argStartAddress[i], Memory.WORD_LENGTH_BYTES)
                stackAddress -= Memory.WORD_LENGTH_BYTES
            }
            Globals.memory.set(stackAddress, argStartAddress.size, Memory.WORD_LENGTH_BYTES) // argc
            stackAddress -= Memory.WORD_LENGTH_BYTES

            // Need to set $sp register to stack address, $a0 to argc, $a1 to argv
            // Need to by-pass the backstepping mechanism so go directly to Register instead of RegisterFile
            getUserRegister("\$sp")!!.setValue(stackAddress + Memory.WORD_LENGTH_BYTES)
            getUserRegister("\$a0")!!.setValue(argStartAddress.size) // argc
            getUserRegister("\$a1")!!.setValue(stackAddress + Memory.WORD_LENGTH_BYTES + Memory.WORD_LENGTH_BYTES) // argv
        } catch (aee: AddressErrorException) {
            println("Internal Error: Memory write error occurred while storing program arguments! $aee")
            exitProcess(1)
        }
    }
}