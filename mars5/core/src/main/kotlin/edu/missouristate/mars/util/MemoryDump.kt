package edu.missouristate.mars.util

import edu.missouristate.mars.mips.hardware.MemoryState

/*
Copyright (c) 2003-2009,  Pete Sanderson and Kenneth Vollmar

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

/** Memory bounds and initialized addresses used by CLI memory exports. */
open class MemoryDump {
    companion object {
        private val segmentNames = arrayOf(".text", ".data")

        /** Return inclusive base and exclusive limit for the given dump segment. */
        @JvmStatic
        fun getSegmentBounds(segment: String?, memory: MemoryState): Array<Int>? {
            val index = segmentNames.indexOf(segment)
            if (index < 0) return null
            return arrayOf(getBaseAddresses(segmentNames, memory)[index], getLimitAddresses(segmentNames, memory)[index])
        }

        @JvmStatic
        fun getSegmentNames(): Array<String> = segmentNames

        @JvmStatic
        @Suppress("unused", "RedundantSuppression")
        fun getBaseAddresses(segments: Array<String>?, memory: MemoryState): IntArray =
            intArrayOf(memory.textBaseAddress, memory.dataBaseAddress)

        @JvmStatic
        @Suppress("unused", "RedundantSuppression")
        fun getLimitAddresses(segments: Array<String>?, memory: MemoryState): IntArray =
            intArrayOf(memory.textLimitAddress, memory.dataSegmentLimitAddress)
    }
}
