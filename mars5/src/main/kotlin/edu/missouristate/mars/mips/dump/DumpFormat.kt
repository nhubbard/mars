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

package edu.missouristate.mars.mips.dump

import edu.missouristate.mars.mips.hardware.AddressErrorException
import java.io.File
import java.io.IOException

/**
 * Interface for memory dump file formats.  All MARS needs to be able
 * to do is save an assembled program or data in the specified manner for
 * a given format.  Formats are specified through classes
 * that implement this interface.
 *
 * @author Pete Sanderson
 * @version December 2007
 */
interface DumpFormat {
    /**
     * Get the file extension associated with this format.
     *
     * @return String containing file extension -- without the leading "." -- or
     * null if there is no standard extension.
     */
    val fileExtension: String

    /**
     * Get a short description of the format, suitable
     * for displaying along with the extension, if any, in the file
     * save dialog and also for displaying as a tool tip.
     *
     * @return String containing short description to go with the extension
     * or as tool tip when mouse hovers over GUI component representing
     * this format.
     */
    val description: String

    /**
     * A short one-word descriptor that will be used by the MARS
     * command line parser (and the MARS command line user) to specify
     * that this format is to be used.
     */
    val commandDescriptor: String?

    /**
     * Descriptive name for the format.
     *
     * @return Format name.
     */
    override fun toString(): String

    /**
     * Write MIPS memory contents according to the
     * specification for this format.
     *
     * @param file         File in which to store MIPS memory contents.
     * @param firstAddress first (lowest) memory address to dump.  In bytes but
     * must be on word boundary.
     * @param lastAddress  last (highest) memory address to dump.  In bytes but
     * must be on word boundary.  Will dump the word that starts at this address.
     * @throws AddressErrorException if firstAddress is invalid or not on a word boundary.
     * @throws IOException           if error occurs during file output.
     */
    @Throws(AddressErrorException::class, IOException::class)
    fun dumpMemoryRange(file: File?, firstAddress: Int, lastAddress: Int)
}