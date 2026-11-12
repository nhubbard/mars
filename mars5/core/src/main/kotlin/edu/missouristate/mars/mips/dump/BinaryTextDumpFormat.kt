package edu.missouristate.mars.mips.dump

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream

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

open class BinaryTextDumpFormat : AbstractDumpFormat("Binary Text", "BinaryText", "Written as '0' and '1' characters to text file", null) {
    @Throws(IOException::class)
    override fun write(file: File, snapshot: MemoryDumpSnapshot) {
        PrintStream(FileOutputStream(file)).use { out ->
            for (index in 0 until snapshot.size()) {
                val word = snapshot.word(index)
                out.println(Integer.toBinaryString(word).padStart(32, '0'))
            }
            checkOutput(out)
        }
    }
}
