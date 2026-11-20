package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.grading.FileAccess.Operation
import java.io.ByteArrayOutputStream

/** One simulated file operation, independent of host files and descriptor state. */
data class FileAccess(
    val operation: Operation,
    val path: String?,
    val descriptor: Int,
    val requestedBytes: Int = 0,
    val result: Int = 0,
    val flags: Int = 0,
) {
    enum class Operation { OPEN, READ, WRITE, CLOSE }
}

/** Detached observations for the in-memory files explicitly supplied by a grading case. */
class FileAccessCheck internal constructor(private val files: GradingFiles) {
    fun snapshot(): List<FileAccess> = files.snapshot()

    /** A copy of a fixture file, including output written so far; missing files fail the case. */
    fun readFile(path: String): ByteArray = files.readFile(path)

    fun expectFile(path: String, expected: ByteArray) {
        requireCase(readFile(path).contentEquals(expected)) { "Unexpected fixture file contents: $path" }
    }

    /** A program must close every successfully opened descriptor on every return path. */
    fun expectAllClosed() {
        requireCase(files.openCount == 0) { "Expected all fixture files closed, found ${files.openCount} open" }
    }
}

internal class GradingFiles {
    private data class OpenFile(
        val path: String,
        val flags: Int,
        var position: Int = 0,
        var written: Int = 0,
        val output: ByteArrayOutputStream? = null,
    )

    private var contents = mutableMapOf<String, ByteArray>()
    private var writablePaths = emptySet<String>()
    private var maxWriteSize = Int.MAX_VALUE
    private var failWriteAfterBytes: Int? = null
    private var writeFailurePositions = emptyMap<String, Int>()
    private var maxFileSize = 1024 * 1024
    private val handles = mutableMapOf<Int, OpenFile>()
    private val accesses = mutableListOf<FileAccess>()
    private var maxReadSize = Int.MAX_VALUE
    private var failAfterBytes: Int? = null
    private var firstDescriptor = 3
    var enabled: Boolean = false
        private set
    val openCount: Int get() = handles.size
    val writesEnabled: Boolean get() = enabled && writablePaths.isNotEmpty()

    fun configure(
        files: Map<String, ByteArray>,
        maximumRead: Int,
        failurePosition: Int?,
        minimumDescriptor: Int = 3,
        writable: Set<String> = emptySet(),
        maximumWrite: Int = Int.MAX_VALUE,
        writeFailurePosition: Int? = null,
        maximumFileSize: Int = 1024 * 1024,
        writeFailuresByPath: Map<String, Int> = emptyMap(),
    ): FileAccessCheck {
        requireCase(!enabled) { "Provide fixture files only once per case" }
        require(maximumRead > 0) { "Maximum read size must be positive" }
        require(failurePosition == null || failurePosition >= 0) { "Read failure position must be nonnegative" }
        require(minimumDescriptor in 3..31) { "First fixture descriptor must be between 3 and 31" }
        require(files.keys.all { it.isNotBlank() && '\u0000' !in it }) { "Choose nonempty file paths without NUL" }
        require(writable.all { it.isNotBlank() && '\u0000' !in it }) { "Choose nonempty writable paths without NUL" }
        require(maximumWrite > 0) { "Maximum write size must be positive" }
        require(writeFailurePosition == null || writeFailurePosition >= 0) { "Write failure position must be nonnegative" }
        require(writeFailuresByPath.keys.all { it in writable }) { "Write failure paths must be declared writable" }
        require(writeFailuresByPath.values.all { it >= 0 }) { "Write failure positions must be nonnegative" }
        require(maximumFileSize > 0) { "Maximum fixture file size must be positive" }
        contents = files.mapValues { (_, bytes) -> bytes.copyOf() }.toMutableMap()
        writablePaths = writable.toSet()
        maxWriteSize = maximumWrite
        failWriteAfterBytes = writeFailurePosition
        writeFailurePositions = writeFailuresByPath.toMap()
        maxFileSize = maximumFileSize
        maxReadSize = maximumRead
        failAfterBytes = failurePosition
        firstDescriptor = minimumDescriptor
        enabled = true
        return FileAccessCheck(this)
    }

    fun snapshot(): List<FileAccess> = accesses.toList()

    fun open(path: String?, flags: Int): Int {
        val allowed = path != null && when (flags) {
            0 -> path in contents
            1, 9 -> path in writablePaths
            else -> false
        }
        val descriptor = if (!allowed || handles.values.any { it.path == path }) -1
        else (firstDescriptor..31).firstOrNull { it !in handles } ?: -1
        if (descriptor >= 0) {
            val name = requireNotNull(path)
            if (flags == 1 || name !in contents) contents[name] = byteArrayOf()
            val output = if (flags == 0) null else ByteArrayOutputStream().also {
                if (flags == 9) it.writeBytes(contents.getValue(name))
            }
            handles[descriptor] = OpenFile(name, flags, output?.size() ?: 0, output = output)
        }
        accesses += FileAccess(Operation.OPEN, path, descriptor, result = descriptor, flags = flags)
        return descriptor
    }

    fun read(descriptor: Int, buffer: ByteArray?, requested: Int): Int {
        val handle = handles[descriptor]
        val result = when {
            handle == null || handle.flags != 0 || buffer == null || requested !in 0..buffer.size -> -1
            requested == 0 -> 0
            failAfterBytes?.let { handle.position >= it } == true -> -1
            else -> {
                val bytes = contents.getValue(handle.path)
                val count = minOf(requested, maxReadSize, bytes.size - handle.position,
                    failAfterBytes?.minus(handle.position) ?: Int.MAX_VALUE)
                bytes.copyInto(buffer, 0, handle.position, handle.position + count)
                handle.position += count
                count
            }
        }
        accesses += FileAccess(Operation.READ, handle?.path, descriptor, requested, result)
        return result
    }

    fun readFile(path: String): ByteArray {
        val pending = handles.values.firstOrNull { it.path == path }?.output
        if (pending != null) return pending.toByteArray()
        val bytes = contents[path]
        requireCase(bytes != null) { "Missing fixture file: $path" }
        return requireNotNull(bytes).copyOf()
    }

    fun write(descriptor: Int, buffer: ByteArray?, requested: Int): Int {
        val handle = handles[descriptor]
        val failurePosition = writeFailurePositions[handle?.path] ?: failWriteAfterBytes
        val result = when {
            handle == null || handle.flags == 0 || buffer == null || requested !in 0..buffer.size -> -1
            requested == 0 -> 0
            handle.position >= maxFileSize || failurePosition?.let { handle.written >= it } == true -> -1
            else -> {
                val count = minOf(requested, maxWriteSize, maxFileSize - handle.position,
                    failurePosition?.minus(handle.written) ?: Int.MAX_VALUE)
                // Grow the output amortized, rather than copying the complete prefix on every short write.
                requireNotNull(handle.output).write(buffer, 0, count)
                handle.position += count
                handle.written += count
                count
            }
        }
        accesses += FileAccess(Operation.WRITE, handle?.path, descriptor, requested, result)
        return result
    }

    fun close(descriptor: Int) {
        val handle = handles.remove(descriptor)
        handle?.output?.let { contents[handle.path] = it.toByteArray() }
        accesses += FileAccess(Operation.CLOSE, handle?.path, descriptor,
            result = if (handle == null) -1 else 0)
    }
}
