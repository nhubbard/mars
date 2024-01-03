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
package edu.missouristate.mars.tools

/**
 * Interface for any tool that interacts with an executing MIPS program.
 * A qualifying tool must be a class in the Tools package that
 * implements the MarsTool interface, must be compiled into a .class file,
 * and its .class file must be in the same Tools folder as MarsTool.class.
 * Mars will detect a qualifying tool upon startup, create an instance
 * using its no-argument constructor and add it to its Tools menu.
 * When its menu item is selected, the action() method will be invoked.
 *
 *
 * A tool may receive communication from MIPS system resources
 * (registers or memory) by registering as an Observer with
 * Mars.Memory and/or Mars.Register objects.
 *
 *
 * It may also
 * communicate directly with those resources through their
 * published methods PROVIDED any such communication is
 * wrapped inside a block synchronized on the
 * Mars.Globals.memoryAndRegistersLock object.
 */
interface MarsTool {
    /**
     * Return a name you have chosen for this tool.  It will appear as the
     * menu item.
     */
    val toolName: String

    /**
     * Performs tool functions.  It will be invoked when the tool is selected
     * from the Tools menu.
     */
    fun action()
}