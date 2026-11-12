package edu.missouristate.mars.mips.hardware

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

/** A memory layout snapshot. The ordered names and values are copied at both boundaries. */
class MemoryConfiguration(
    private val configurationIdentifier: String?,
    private val configurationName: String?,
    configurationItemNames: Array<String>,
    configurationItemValues: IntArray,
) {
    private val itemNames = configurationItemNames.copyOf()
    private val itemValues = configurationItemValues.copyOf()

    fun configurationIdentifier(): String? = configurationIdentifier
    fun configurationName(): String? = configurationName
    fun configurationItemNames(): Array<String> = itemNames.copyOf()
    fun configurationItemValues(): IntArray = itemValues.copyOf()

    val textBaseAddress: Int get() = itemValues[0]
    val dataSegmentBaseAddress: Int get() = itemValues[1]
    val externBaseAddress: Int get() = itemValues[2]
    val globalPointer: Int get() = itemValues[3]
    val dataBaseAddress: Int get() = itemValues[4]
    val heapBaseAddress: Int get() = itemValues[5]
    val stackPointer: Int get() = itemValues[6]
    val stackBaseAddress: Int get() = itemValues[7]
    val userHighAddress: Int get() = itemValues[8]
    val kernelBaseAddress: Int get() = itemValues[9]
    val kernelTextBaseAddress: Int get() = itemValues[10]
    val exceptionHandlerAddress: Int get() = itemValues[11]
    val kernelDataBaseAddress: Int get() = itemValues[12]
    val memoryMapBaseAddress: Int get() = itemValues[13]
    val kernelHighAddress: Int get() = itemValues[14]
    val dataSegmentLimitAddress: Int get() = itemValues[15]
    val textLimitAddress: Int get() = itemValues[16]
    val kernelDataSegmentLimitAddress: Int get() = itemValues[17]
    val kernelTextLimitAddress: Int get() = itemValues[18]
    val stackLimitAddress: Int get() = itemValues[19]
    val memoryMapLimitAddress: Int get() = itemValues[20]
}
