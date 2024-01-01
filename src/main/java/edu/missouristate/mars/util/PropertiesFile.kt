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

package edu.missouristate.mars.util

import java.io.IOException
import java.util.*

/**
 * Provides means to work with ".properties" files which are used to store
 * various MARS settings.
 *
 * @author Pete Sanderson
 * @version October 2006
 */
object PropertiesFile {
    /**
     * Produce Properties (a Hashtable) object containing key-value pairs
     * from specified properties file.  This may be used as an alternative
     * to readPropertiesFile() which uses a different implementation.
     *
     * @param file The properties file name.
     * Do NOT include the file extension as it is assumed to be ".properties" and is added here.
     * @return Properties (Hashtable) of key-value pairs read from the file.
     */
    @JvmStatic
    fun loadPropertiesFromFile(file: String): Properties {
        val properties = Properties()
        try {
            val `is` = PropertiesFile::class.java.getResourceAsStream("/$file.properties")
            properties.load(`is`)
        } catch (ignored: IOException) {}

        return properties
    }
}

