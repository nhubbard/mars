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

import edu.missouristate.mars.Globals
import edu.missouristate.mars.Settings
import java.io.*
import kotlin.math.min

/**
 * Provides standard I/O services needed to simulate the MIPS syscall routines.
 * These methods will detect whether the simulator is being run from the command line or through the GUI, and do I/O
 * with `System.in` and `System.out` for the command line, or through the GUI.
 */
object SystemIO {
    const val SYSCALL_BUFSIZE = 128
    const val SYSCALL_MAXFILES = 32

    @JvmStatic var fileErrorMessage = "File operation OK"
        private set

    private const val O_RDONLY = 0x0
    private const val O_WRONLY = 0x1
    private const val O_RDWR = 0x2
    private const val O_APPEND = 0x8
    private const val O_CREAT = 0x200
    private const val O_TRUNC = 0x400
    private const val O_EXCL = 0x800

    private const val STDIN = 0
    private const val STDOUT = 1
    private const val STDERR = 2

    @JvmStatic
    private val inputReader by lazy { BufferedReader(InputStreamReader(System.`in`)) }

    /**
     * Remove the repeated process of grabbing value from either GUI or console.
     */
    @JvmStatic
    private fun promptHelper(
        message: String,
        maxLength: Int = -1,
        defaultValue: String = "0",
        dropNewline: Boolean = false
    ): String {
        var input = defaultValue
        Globals.gui?.messagesPane?.let {
            input = if (Globals.settings.getBooleanSetting(Settings.POPUP_SYSCALL_INPUT)) {
                it.getInputString(message)
            } else {
                it.getInputString(maxLength)
            }
            if (dropNewline && input.endsWith("\n"))
                input = input.dropLast(1)
        } ?: run {
            try {
                input = inputReader.readLine() ?: input
            } catch (ignored: Exception) {}
        }
        return input.trim()
    }

    /**
     * Implements syscall to read an integer value.
     * Client is responsible for catching NumberFormatException.
     *
     * @param serviceNumber the number assigned to Read Int syscall (default 5)
     * @return int value corresponding to user input
     */
    @JvmStatic
    fun readInteger(serviceNumber: Int): Int =
        promptHelper("Enter an integer value (syscall $serviceNumber)").toInt()

    /**
     * Implements syscall to read a float value.
     * Client is responsible for catching NumberFormatException.
     *
     * @param serviceNumber the number assigned to Read Float syscall (default 6)
     * @return float value corresponding to user input
     * Feb 14 2005 Ken Vollmar
     */
    @JvmStatic
    fun readFloat(serviceNumber: Int): Float =
        promptHelper("Enter a float value (syscall $serviceNumber)").toFloat()

    /**
     * Implements syscall to read a double value.
     * Client is responsible for catching NumberFormatException.
     *
     * @param serviceNumber the number assigned to Read Double syscall (default 7)
     * @return double value corresponding to user input
     * 1 Aug 2005 DPS, based on Ken Vollmar's readFloat
     */
    @JvmStatic
    fun readDouble(serviceNumber: Int): Double =
        promptHelper("Enter a double value (syscall $serviceNumber)").toDouble()

    @JvmStatic
    fun printString(string: String) {
        Globals.gui?.messagesPane?.postRunMessage(string) ?: print(string)
    }

    /**
     * Implements syscall to read a string.
     *
     * @param serviceNumber the number assigned to Read String syscall (default 8)
     * @param maxLength     the maximum string length
     * @return the entered string, truncated to maximum length if necessary
     */
    @JvmStatic
    fun readString(serviceNumber: Int, maxLength: Int): String {
        val input = promptHelper(
            "Enter a string of maximum length $maxLength (syscall $serviceNumber)",
            maxLength = maxLength,
            defaultValue = "",
            dropNewline = true
        )
        return if (input.length > maxLength) {
            if (maxLength <= 0) "" else input.substring(0, maxLength)
        } else input
    }

    /**
     * Implements syscall having 12 in $v0, to read a char value.
     *
     * @param serviceNumber the number assigned to Read Char syscall (default 12)
     * @return int value with the lowest byte corresponding to user input
     */
    @JvmStatic
    fun readChar(serviceNumber: Int): Char {
        val input = promptHelper(
            "Enter a character value (syscall $serviceNumber)",
            maxLength = 1
        )
        return input[0]
    }

    // TODO: Rewrite these functions using Okio for efficiency/performance.

    /**
     * Write bytes to a file.
     *
     * @param fd The file descriptor to write the bytes to
     * @param buffer The byte array containing the characters to write
     * @param lengthRequested The number of bytes to write
     * @return The number of bytes written, or -1 if an error occurred
     */
    @JvmStatic
    fun writeToFile(fd: Int, buffer: ByteArray, lengthRequested: Int): Int {
        // When writing to standard out or standard error file descriptor using the IDE, the message is redirected
        // to the Messages panel.
        if ((fd == STDOUT || fd == STDERR) && Globals.gui != null) {
            val data = String(buffer)
            Globals.gui!!.messagesPane.postRunMessage(data)
            return data.length
        }
        // When running in command-line mode, the code below works for either a regular file or standard out/error.
        if (FileIOData.fdNotInUse(fd, 1)) {
            fileErrorMessage = "File descriptor $fd is not open for writing."
            return -1
        }
        val outputStream = FileIOData.getStreamInUse(fd) as OutputStream
        try {
            for (i in 0..<lengthRequested) outputStream.write(buffer[i].toInt())
            outputStream.flush()
        } catch (e: IOException) {
            fileErrorMessage = "IOException on write of file with FD $fd!"
            return -1
        } catch (e: IndexOutOfBoundsException) {
            fileErrorMessage = "IndexOutOfBoundsException on write of file with FD $fd!"
            return -1
        }
        return lengthRequested
    }

    /**
     * Read bytes from a file.
     *
     * @param fd The file descriptor to read the bytes from
     * @param buffer The byte array to output the bytes to
     * @param lengthRequested The number of bytes to read.
     */
    @JvmStatic
    fun readFromFile(fd: Int, buffer: ByteArray, lengthRequested: Int): Int {
        var retValue: Int
        // Read from STDIN file descriptor while using IDE is redirected to Messages panel input.
        Globals.gui?.messagesPane?.let {
            if (fd == STDIN) {
                val input = it.getInputString(lengthRequested)
                val bytesRead = input.encodeToByteArray()
                for (i in buffer.indices)
                    buffer[i] = if (i < bytesRead.size) bytesRead[i] else 0
                return min(buffer.size, bytesRead.size)
            }
        }
        // If the let statement for the GUI does not execute, we are running in command mode.
        if (FileIOData.fdNotInUse(fd, 0)) {
            fileErrorMessage = "File descriptor $fd is not open for reading!"
            return -1
        }
        // Retrieve the InputStream from FileIOData
        val inputStream = FileIOData.getStreamInUse(fd) as InputStream
        try {
            // Read up to lengthRequested bytes of data from the stream into an array of bytes.
            retValue = inputStream.read(buffer, 0, lengthRequested)
            // This method will return -1 upon EOF, but out spec says that a negative return value represents an error,
            // so we return 0 for EOF.
            if (retValue == -1) retValue = 0
        } catch (e: IOException) {
            fileErrorMessage = "IOException on read of file with FD $fd!"
            return -1
        } catch (e: IndexOutOfBoundsException) {
            fileErrorMessage = "IndexOutOfBoundsException on read of file with FD $fd!"
            return -1
        }
        return retValue
    }

    /**
     * Open a file for either reading or writing. Note that the read/write flag is NOT
     * IMPLEMENTED. Also note that file permission modes are also NOT IMPLEMENTED.
     *
     * @param filename string containing filename
     * @param flags    Must be `0` for read or `1` for write
     * @return file descriptor in the range `0..<SYSCALL_MAXFILES`, or -1 if an error occurs
     * @author Ken Vollmar
     */
    @JvmStatic
    fun openFile(filename: String, flags: Int): Int {
        // Internally, a "file descriptor" is an index into a table of the filename, flag, and the File{In,Out}putStream
        // associated with that file descriptor.
        var retValue: Int
        val inputStream: FileInputStream
        val outputStream: FileOutputStream

        // Check the internal plausibility of opening this file
        val fdToUse = FileIOData.nowOpening(filename, flags)
        retValue = fdToUse
        if (fdToUse < 0) return -1

        if (flags == O_RDONLY) {
            try {
                // Set up input stream from disk file
                inputStream = FileInputStream(filename)
                // Save stream for later use
                FileIOData.setStreamInUse(fdToUse, inputStream)
            } catch (e: FileNotFoundException) {
                fileErrorMessage = "File $filename not found; cannot open for input!"
                retValue = -1
            }
        } else if ((flags and O_WRONLY) != 0) {
            // Set up output stream to disk file
            try {
                outputStream = FileOutputStream(filename, (flags and O_APPEND) != 0)
                FileIOData.setStreamInUse(fdToUse, outputStream)
            } catch (e: FileNotFoundException) {
                fileErrorMessage = "File $filename not found; cannot open for output!"
                retValue = -1
            }
        }
        return retValue
    }

    /**
     * Close the file with the specified file descriptor.
     */
    @JvmStatic
    fun closeFile(fd: Int) = FileIOData.close(fd)

    /**
     * Reset all files. Clears the file descriptor table.
     */
    @JvmStatic
    fun resetFiles() = FileIOData.resetFiles()

    private object FileIOData {
        @JvmStatic private val fileNames = arrayOfNulls<String>(SYSCALL_MAXFILES)
        @JvmStatic private val fileFlags = IntArray(SYSCALL_MAXFILES)
        @JvmStatic private val streams = arrayOfNulls<Any>(SYSCALL_MAXFILES)

        @JvmStatic
        fun resetFiles() {
            for (i in 0..<SYSCALL_MAXFILES) close(i)
            setupStdio()
        }

        @JvmStatic
        fun setupStdio() {
            fileNames[STDIN] = "STDIN"
            fileNames[STDOUT] = "STDOUT"
            fileNames[STDERR] = "STDERR"
            fileFlags[STDIN] = O_RDONLY
            fileFlags[STDOUT] = O_WRONLY
            fileFlags[STDERR] = O_WRONLY
            streams[STDIN] = System.`in`
            streams[STDOUT] = System.out
            streams[STDERR] = System.err
            System.out.flush()
            System.err.flush()
        }

        @JvmStatic
        fun setStreamInUse(fd: Int, s: Any?) {
            streams[fd] = s
        }

        @JvmStatic
        fun getStreamInUse(fd: Int): Any? = streams[fd]

        /** Determine whether a given filename is already in use. */
        @JvmStatic
        fun filenameInUse(requestedFilename: String): Boolean =
            fileNames.any { it.equals(requestedFilename) }

        /** Determine whether a given file descriptor is already in use with the given flag. */
        @JvmStatic
        fun fdNotInUse(fd: Int, flag: Int): Boolean =
            if (fd < 0 || fd >= SYSCALL_MAXFILES) true
            else if (fileNames[fd] != null && fileFlags[fd] == 0 && flag == 0) false
            else fileNames[fd] == null || ((fileFlags[fd] and flag and O_WRONLY) != O_WRONLY)

        /**
         * Close the file with the file descriptor [fd]. No errors are recoverable; if the user made an error in the
         * call, it will come back to them.
         */
        @JvmStatic
        fun close(fd: Int) {
            // Can't close STDIN, STDOUT, STDERR, or invalid file descriptor
            if (fd <= STDERR || fd >= SYSCALL_MAXFILES) return
            fileNames[fd] = null
            // All this code will be executed only if the descriptor is open.
            if (streams[fd] != null) {
                val keepFlag = fileFlags[fd]
                val keepStream = streams[fd]
                fileFlags[fd] = -1
                streams[fd] = null
                try {
                    if (keepFlag == O_RDONLY) (keepStream as FileInputStream).close()
                    else (keepStream as FileOutputStream).close()
                } catch (ignored: IOException) {}
            } else {
                fileFlags[fd] = -1
            }
        }

        /**
         * Attempt to open a new file with the given flag, using the lowest available file descriptor.
         * Check that the filename is not in use, the flag is reasonable, and there is an available file descriptor.
         *
         * @return The file descriptor in the range `0..<SYSCALL_MAXFILES`, or -1 if there is an error.
         */
        @JvmStatic
        fun nowOpening(filename: String, flag: Int): Int {
            var i = 0
            if (filenameInUse(filename)) {
                fileErrorMessage = "File name $filename is already open!"
                return -1
            }
            // Only read and write are implemented
            if (flag != O_RDONLY && flag != O_WRONLY && flag != (O_WRONLY or O_APPEND)) {
                fileErrorMessage = "File name $filename has unknown requested opening flag!"
                return -1
            }
            // Attempt to find the first available descriptor.
            while (fileNames[i] != null && i < SYSCALL_MAXFILES) i++
            if (i >= SYSCALL_MAXFILES) {
                fileErrorMessage = "File name $filename exceeds maximum open file limit of $SYSCALL_MAXFILES!"
                return -1
            }
            // Must be okay at this point. Put the filename in the table.
            fileNames[i] = filename
            fileFlags[i] = flag
            fileErrorMessage = "File operation OK."
            return i
        }
    }
}