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

package edu.missouristate.mars.mips.dump

import edu.missouristate.mars.util.FilenameFinder
import kotlin.reflect.full.createInstance

class DumpFormatLoader {
    companion object {
        private const val CLASS_PREFIX = "edu.missouristate.mars.mips.dump."
        private const val DUMP_DIRECTORY_PATH = "edu/missouristate/mars/mipsmissouristate/mars/mips/dump"
        private const val SYSCALL_INTERFACE = "DumpFormat.class"
        private const val CLASS_EXTENSION = "class"
        private var formatList: ArrayList<DumpFormat>? = null

        /** Find a dump format from its command descriptor. */
        @JvmStatic
        fun findDumpFormatGivenCommandDescriptor(
            formatList: ArrayList<DumpFormat>,
            descriptor: String
        ): DumpFormat? = formatList.firstOrNull { it.commandDescriptor == descriptor }
    }

    /**
     * Dynamically loads dump formats into an ArrayList.
     */
    fun loadDumpFormats(): ArrayList<DumpFormat> {
        if (formatList == null) {
            formatList = arrayListOf()
            val candidates = FilenameFinder.getFilenameList(javaClass.classLoader, DUMP_DIRECTORY_PATH, CLASS_EXTENSION)
            for (file in candidates) {
                try {
                    // Grab the class, make sure it implements DumpFormat, create an instance, and add to list.
                    val formatClassName = CLASS_PREFIX + file.substringBeforeLast(CLASS_EXTENSION).dropLast(1)
                    val kClass = Class.forName(formatClassName).kotlin
                    if (DumpFormat::class.java.isAssignableFrom(kClass.java) && !kClass.isAbstract && !kClass.java.isInterface) {
                        // Create instance and add to list
                        val instance = kClass.createInstance() as DumpFormat
                        formatList!!.add(instance)
                    }
                } catch (e: Exception) {
                    println("Error instantiating DumpFormat from file $file: ${e.message}")
                }
            }
        }
        return formatList!!
    }
}