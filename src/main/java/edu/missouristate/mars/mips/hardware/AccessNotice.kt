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

package edu.missouristate.mars.mips.hardware

/**
 * Object provided to Observers of runtime access to MIPS memory or registers.
 * The access types READ and WRITE defined here; use subclasses defined for MemoryAccessNotice and RegisterAccessNotice.
 * This is an abstract class.
 */
abstract class AccessNotice protected constructor(
    val accessType: AccessType
) {
    enum class AccessType(val rawValue: Int) {
        READ(0), WRITE(1)
    }

    val thread: Thread = Thread.currentThread()

    /**
     * Query whether the access originated from MARS GUI (AWT event queue).
     */
    val accessIsFromGUI: Boolean get() = thread.name.startsWith("AWT")

    /**
     * Query whether the access originated from an executed MIPS program.
     */
    val accessIsFromMIPS: Boolean get() = thread.name.startsWith("MIPS")
}