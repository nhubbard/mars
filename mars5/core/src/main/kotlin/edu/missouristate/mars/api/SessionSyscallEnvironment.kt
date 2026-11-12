package edu.missouristate.mars.api

import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.instructions.syscalls.ToneGenerator
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction.REPLACE
import java.nio.file.Path

/**
 * Headless I/O owned by one session. Descriptors 0, 1, and 2 are the supplied console streams.
 * Descriptors 3 through 31 are private and can be closed without closing the console streams.
 * Call from the execution thread or while the session is stopped.
 */
class SessionSyscallEnvironment @JvmOverloads constructor(
    private val memory: MemoryState,
    private val input: InputStream,
    private val output: PrintStream,
    private val error: PrintStream,
    private val workingDirectory: Path = Path.of(""),
    inputCharset: Charset = Charset.defaultCharset(),
    textInput: TextInput? = null,
) : SyscallEnvironment, AutoCloseable {
    constructor(memory: MemoryState) : this(memory, System.`in`, System.out, System.err)

    enum class ReadKind { INTEGER, FLOAT, DOUBLE, STRING, CHARACTER }

    /** Supplies typed reads without changing descriptor 0. Returns null on end of input. */
    fun interface TextInput {
        fun read(kind: ReadKind, maximumLength: Int): String?
    }

    private val textInput = textInput ?: TextInput { _, _ -> readLine() }
    private val inputDecoder = inputCharset.newDecoder().onMalformedInput(REPLACE).onUnmappableCharacter(REPLACE)
    private var inputBytes = ByteBuffer.allocate(8)
    private val inputChars = CharBuffer.allocate(8)
    private val tones = ToneGenerator { failure -> error.println("MARS MIDI playback failed. $failure") }
    private val files = arrayOfNulls<Closeable>(32)
    private val paths = arrayOfNulls<String>(32)

    override fun playTone(pitch: Int, duration: Int, instrument: Int, volume: Int, synchronous: Boolean) {
        if (synchronous) {
            tones.generateToneSynchronously(pitch.toByte(), duration, instrument.toByte(), volume.toByte())
        } else {
            tones.generateTone(pitch.toByte(), duration, instrument.toByte(), volume.toByte())
        }
    }

    override fun printString(s: String?) {
        output.print(s)
        output.flush()
    }

    override fun printInt(value: Int) = printString(value.toString())

    override fun printFloat(value: Float) = printString(value.toString())

    override fun printDouble(value: Double) = printString(value.toString())

    override fun printChar(c: Char) = printString(c.toString())

    private fun readLine(): String? {
        val line = StringBuilder()
        try {
            while (true) {
                val value = input.read()
                val end = value == -1
                // Decode one byte at a time so a descriptor 0 read resumes exactly after this line.
                if (!end) {
                    if (!inputBytes.hasRemaining()) {
                        inputBytes = ByteBuffer.allocate(inputBytes.capacity() * 2).put(inputBytes.flip())
                    }
                    inputBytes.put(value.toByte())
                }
                inputBytes.flip()
                var result: CoderResult
                do {
                    result = inputDecoder.decode(inputBytes, inputChars, end)
                    appendDecodedInput(line)
                } while (result.isOverflow)
                inputBytes.compact()
                if (end) {
                    do {
                        result = inputDecoder.flush(inputChars)
                        appendDecodedInput(line)
                    } while (result.isOverflow)
                    inputDecoder.reset()
                    if (line.isEmpty()) return null
                    break
                }
                if (line.lastOrNull() == '\n') {
                    line.setLength(line.length - 1)
                    break
                }
            }
            if (line.lastOrNull() == '\r') {
                line.setLength(line.length - 1)
            }
            return line.toString()
        } catch (failure: IOException) {
            throw IllegalStateException("Unable to read console input", failure)
        }
    }

    private fun appendDecodedInput(line: StringBuilder) {
        line.append(inputChars.flip())
        inputChars.clear()
    }

    override fun readString(maxLength: Int): String? =
        textInput.read(ReadKind.STRING, maxLength)?.let { it.substring(0, maxLength.coerceIn(0, it.length)) }

    // Java String.trim() only removes characters through U+0020.
    private fun numericInput(kind: ReadKind): String = textInput.read(kind, -1).orEmpty().trim { it <= ' ' }

    override fun readInt(): Int = Integer.parseInt(numericInput(ReadKind.INTEGER))

    override fun readFloat(): Float = numericInput(ReadKind.FLOAT).toFloat()

    override fun readDouble(): Double = numericInput(ReadKind.DOUBLE).toDouble()

    override fun readChar(): Char = textInput.read(ReadKind.CHARACTER, 1).orEmpty()[0]

    override fun exit(code: Int) {
        // The instruction signals termination.
    }

    override fun openFile(path: String?, flags: Int): Int {
        if (path == null || (flags != 0 && flags != 1 && flags != 9)) return -1
        for (fd in 3 until files.size) {
            if (path == paths[fd]) return -1
        }
        for (fd in 3 until files.size) {
            if (files[fd] != null) continue
            try {
                val file = workingDirectory.resolve(path).toFile()
                files[fd] = if (flags == 0) FileInputStream(file) else FileOutputStream(file, flags == 9)
                paths[fd] = path
                return fd
            } catch (_: IOException) {
                return -1
            } catch (_: SecurityException) {
                return -1
            } catch (_: IllegalArgumentException) {
                return -1
            }
        }
        return -1
    }

    override fun readFromFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int {
        if (buffer == null || lengthRequested !in 0..buffer.size) return -1
        val stream = when (fd) {
            0 -> input
            in 3 until files.size -> files[fd] as? InputStream
            else -> null
        } ?: return -1
        return try {
            stream.read(buffer, 0, lengthRequested).coerceAtLeast(0)
        } catch (_: IOException) {
            -1
        }
    }

    override fun writeToFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int {
        if (buffer == null || lengthRequested !in 0..buffer.size) return -1
        val stream: OutputStream = when (fd) {
            1 -> output
            2 -> error
            in 3 until files.size -> files[fd] as? OutputStream
            else -> null
        } ?: return -1
        return try {
            stream.write(buffer, 0, lengthRequested)
            stream.flush()
            if (stream is PrintStream && stream.checkError()) -1 else lengthRequested
        } catch (_: IOException) {
            -1
        }
    }

    override fun closeFile(fd: Int) {
        if (fd !in 3 until files.size) return
        val file = files[fd] ?: return
        try {
            file.close()
        } catch (_: IOException) {
        } finally {
            files[fd] = null
            paths[fd] = null
        }
    }

    /** Stops asynchronous tones and closes files. The environment can be reused. */
    override fun close() {
        tones.close()
        for (fd in 3 until files.size) closeFile(fd)
    }

    override fun sbrk(bytes: Int): Int = memory.allocateBytesFromHeap(bytes)

    override fun showMessageDialog(message: String?, title: String?, messageType: Int) {
        val prefix = if (title.isNullOrEmpty()) "" else "[$title] "
        error.println(prefix + message)
    }

    override fun showInputDialog(message: String?): String? {
        error.print("$message: ")
        error.flush()
        return readLine()
    }

    override fun showConfirmDialog(message: String?): Int = 0

    override fun clearConsole() {}
}
