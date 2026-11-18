package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.grading.FileAccess.Operation

/** One simulated file operation, independent of host files and descriptor state. */
data class FileAccess(
    val operation: Operation,
    val path: String?,
    val descriptor: Int,
    val requestedBytes: Int = 0,
    val result: Int = 0,
    val flags: Int = 0,
) {
    enum class Operation { OPEN, READ, CLOSE }
}

/** Observations for the read-only files explicitly supplied by a grading case. */
class FileAccessCheck internal constructor(private val files: GradingFiles) {
    fun snapshot(): List<FileAccess> = files.snapshot()

    /** A program must close every successfully opened descriptor on every return path. */
    fun expectAllClosed() {
        requireCase(files.openCount == 0) { "Expected all fixture files closed, found ${files.openCount} open" }
    }
}

internal class GradingFiles {
    private data class OpenFile(val path: String, var position: Int = 0)

    private var contents = emptyMap<String, ByteArray>()
    private val handles = mutableMapOf<Int, OpenFile>()
    private val accesses = mutableListOf<FileAccess>()
    private var maxReadSize = Int.MAX_VALUE
    private var failAfterBytes: Int? = null
    var enabled: Boolean = false
        private set
    val openCount: Int get() = handles.size

    fun configure(files: Map<String, ByteArray>, maximumRead: Int, failurePosition: Int?): FileAccessCheck {
        requireCase(!enabled) { "Provide fixture files only once per case" }
        require(maximumRead > 0) { "Maximum read size must be positive" }
        require(failurePosition == null || failurePosition >= 0) { "Read failure position must be nonnegative" }
        require(files.keys.all { it.isNotBlank() && '\u0000' !in it }) { "Choose nonempty file paths without NUL" }
        contents = files.mapValues { (_, bytes) -> bytes.copyOf() }
        maxReadSize = maximumRead
        failAfterBytes = failurePosition
        enabled = true
        return FileAccessCheck(this)
    }

    fun snapshot(): List<FileAccess> = accesses.toList()

    fun open(path: String?, flags: Int): Int {
        val descriptor = if (flags != 0 || path !in contents || handles.values.any { it.path == path }) -1
        else (3..31).firstOrNull { it !in handles } ?: -1
        if (descriptor >= 0) handles[descriptor] = OpenFile(requireNotNull(path))
        accesses += FileAccess(Operation.OPEN, path, descriptor, result = descriptor, flags = flags)
        return descriptor
    }

    fun read(descriptor: Int, buffer: ByteArray?, requested: Int): Int {
        val handle = handles[descriptor]
        val result = when {
            handle == null || buffer == null || requested !in 0..buffer.size -> -1
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

    fun close(descriptor: Int) {
        val handle = handles.remove(descriptor)
        accesses += FileAccess(Operation.CLOSE, handle?.path, descriptor,
            result = if (handle == null) -1 else 0)
    }
}
