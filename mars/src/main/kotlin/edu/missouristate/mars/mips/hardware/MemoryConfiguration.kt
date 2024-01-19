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

package edu.missouristate.mars.mips.hardware

/**
 * Models the memory configuration for the simulated MIPS machine.
 * "Configuration" refers to the starting memory addresses for the various memory segments.
 * The default configuration is based on SPIM. Starting with MARS 3.7, the configuration can be changed.
 */
class MemoryConfiguration(
    @JvmField val configurationIdentifier: String,
    @JvmField val configurationName: String,
    @JvmField val configurationItemNames: Array<String>,
    @JvmField val configurationItemValues: IntArray
) {
    val textBaseAddress: Int get() = configurationItemValues[0]
    val dataSegmentBaseAddress: Int get() = configurationItemValues[1]
    val externBaseAddress: Int get() = configurationItemValues[2]
    val globalPointer: Int get() = configurationItemValues[3]
    val dataBaseAddress: Int get() = configurationItemValues[4]
    val heapBaseAddress: Int get() = configurationItemValues[5]
    val stackPointer: Int get() = configurationItemValues[6]
    val stackBaseAddress: Int get() = configurationItemValues[7]
    val userHighAddress: Int get() = configurationItemValues[8]
    val kernelBaseAddress: Int get() = configurationItemValues[9]
    val kernelTextBaseAddress: Int get() = configurationItemValues[10]
    val exceptionHandlerAddress: Int get() = configurationItemValues[11]
    val kernelDataBaseAddress: Int get() = configurationItemValues[12]
    val memoryMapBaseAddress: Int get() = configurationItemValues[13]
    val kernelHighAddress: Int get() = configurationItemValues[14]
    val dataSegmentLimitAddress: Int get() = configurationItemValues[15]
    val textLimitAddress: Int get() = configurationItemValues[16]
    val kernelDataSegmentLimitAddress: Int get() = configurationItemValues[17]
    val kernelTextLimitAddress: Int get() = configurationItemValues[18]
    val stackLimitAddress: Int get() = configurationItemValues[19]
    val memoryMapLimitAddress: Int get() = configurationItemValues[20]
}