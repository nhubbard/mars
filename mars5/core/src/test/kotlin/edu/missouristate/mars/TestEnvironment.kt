package edu.missouristate.mars

import edu.missouristate.mars.api.SyscallEnvironment

/** Minimal syscall fixture. Individual tests override the operations their programs use. */
internal abstract class TestEnvironment : SyscallEnvironment {
    override fun printString(s: String?): Unit = throw UnsupportedOperationException()

    override fun printInt(value: Int) = printString(value.toString())

    override fun printFloat(value: Float) = printString(value.toString())

    override fun printDouble(value: Double) = printString(value.toString())

    override fun printChar(c: Char) = printString(c.toString())

    override fun readString(maxLength: Int): String? = throw UnsupportedOperationException()

    override fun readInt(): Int = throw UnsupportedOperationException()

    override fun readFloat(): Float = throw UnsupportedOperationException()

    override fun readDouble(): Double = throw UnsupportedOperationException()

    override fun readChar(): Char = throw UnsupportedOperationException()

    override fun exit(code: Int) = Unit

    override fun openFile(path: String?, flags: Int): Int = throw UnsupportedOperationException()

    override fun readFromFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int =
        throw UnsupportedOperationException()

    override fun writeToFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int =
        throw UnsupportedOperationException()

    override fun closeFile(fd: Int): Unit = throw UnsupportedOperationException()

    override fun sbrk(bytes: Int): Int = throw UnsupportedOperationException()

    override fun showMessageDialog(message: String?, title: String?, messageType: Int): Unit =
        throw UnsupportedOperationException()

    override fun showInputDialog(message: String?): String? = throw UnsupportedOperationException()

    override fun showConfirmDialog(message: String?): Int = throw UnsupportedOperationException()

    override fun clearConsole() = Unit
}
