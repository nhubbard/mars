package edu.missouristate.mars.mips.dump

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import java.io.File
import java.io.IOException

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

/** Formats a captured inclusive range of MIPS memory. */
interface DumpFormat {
    val fileExtension: String?
    val description: String?
    val commandDescriptor: String?

    @Throws(AddressErrorException::class, IOException::class)
    fun dumpMemoryRange(file: File, firstAddress: Int, lastAddress: Int, session: MarsContext) {
        write(file, capture(session, firstAddress, lastAddress) {})
    }

    /** Capture while the caller owns stopped execution. */
    @Throws(AddressErrorException::class)
    fun capture(session: MarsContext, firstAddress: Int, lastAddress: Int, checkCancellation: Runnable): MemoryDumpSnapshot =
        MemoryDumpSnapshot.capture(session, firstAddress, lastAddress, includesSourceListing(), checkCancellation)

    fun includesSourceListing(): Boolean = false

    /** Write a snapshot without accessing the machine. */
    @Throws(IOException::class)
    fun write(file: File, snapshot: MemoryDumpSnapshot)
}
