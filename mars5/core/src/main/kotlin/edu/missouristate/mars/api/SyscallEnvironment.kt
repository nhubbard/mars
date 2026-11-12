package edu.missouristate.mars.api

/** Console, file, process, and device services used by syscalls on the simulator thread. */
interface SyscallEnvironment {
    fun printString(s: String?)
    fun printInt(value: Int)
    fun printFloat(value: Float)
    fun printDouble(value: Double)
    fun printChar(c: Char)

    /** Read a line without its trailing newline. Return null at end of input. */
    fun readString(maxLength: Int): String?

    fun readInt(): Int
    fun readFloat(): Float
    fun readDouble(): Double
    fun readChar(): Char

    /** Signal termination of the simulated program with its exit [code]. */
    fun exit(code: Int)

    /** Open a file for read (0), write (1), or append (9). Return -1 on failure. */
    fun openFile(path: String?, flags: Int): Int

    /** Read at most [lengthRequested] bytes. Return zero at EOF and -1 on failure. */
    fun readFromFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int

    /** Write [lengthRequested] bytes. Return the number written or -1 on failure. */
    fun writeToFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int

    fun closeFile(fd: Int)

    /** Allocate simulated heap space and return its starting address. */
    fun sbrk(bytes: Int): Int

    fun currentTimeMillis(): Long = System.currentTimeMillis()

    fun sleepMillis(milliseconds: Int) {
        try {
            Thread.sleep(milliseconds.toLong())
        } catch (_: InterruptedException) {
            // The synchronous syscall returns when interrupted.
        }
    }

    /** Message types are error (0), information (1), warning (2), question (3), and plain (-1). */
    fun showMessageDialog(message: String?, title: String?, messageType: Int)

    /** Return the entered text, or null if the input was canceled. */
    fun showInputDialog(message: String?): String?

    /** Return yes (0), no (1), or cancel (2). */
    fun showConfirmDialog(message: String?): Int

    fun clearConsole()

    /** Play a normalized MIDI tone. Frontends without audio may keep the default silence. */
    fun playTone(pitch: Int, duration: Int, instrument: Int, volume: Int, synchronous: Boolean) = Unit
}
