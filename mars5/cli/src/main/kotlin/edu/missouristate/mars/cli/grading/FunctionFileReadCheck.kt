package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.grading.FileAccess.Operation
import java.nio.charset.StandardCharsets.UTF_8

/** A per-call path and request bound within an already installed read-only fixture environment. */
data class FunctionFileReadInvocation(val path: String, val maxRequest: Int) {
  init {
    fixturePath(path)
    require(maxRequest in 1..ConsoleMemoryCheck.MAX_BYTES) {
      "Bound each read request through one MiB"
    }
  }
}

internal fun fixturePath(path: String) {
  require(
      path.isNotBlank() &&
          '\u0000' !in path &&
          UTF_8.newEncoder().canEncode(path) &&
          path.toByteArray(UTF_8).size <= 4096,
  ) {
    "Choose a nonempty UTF-8 fixture path through 4096 bytes without NUL"
  }
}

internal fun fixtureBytes(bytes: String) {
  require(
      bytes.length <= ConsoleMemoryCheck.MAX_BYTES * 2 &&
          bytes.length % 2 == 0 &&
          bytes.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' },
  ) {
    "Fixture input needs hexadecimal byte pairs through one MiB, including empty input"
  }
}

/** Copied read-only files and a default sequential-read contract. Never opens host files. */
data class FunctionFileReadCheck(
    val path: String,
    val bytes: String,
    val maxRequest: Int,
    val maxReadSize: Int = ConsoleMemoryCheck.MAX_BYTES,
    val failAfterBytes: Int? = null,
    val firstDescriptor: Int = 3,
    val missing: Boolean = false,
    val additionalFiles: Map<String, String> = emptyMap(),
) {
  init {
    fixturePath(path)
    fixtureBytes(bytes)
    require(additionalFiles.size <= 63 && path !in additionalFiles) {
      "Supply at most 63 additional files with distinct paths"
    }
    additionalFiles.forEach { (name, contents) ->
      fixturePath(name)
      fixtureBytes(contents)
    }
    require(byteLength <= ConsoleMemoryCheck.MAX_BYTES) {
      "Combined read-only files exceed one MiB"
    }
    require(
        maxRequest in 1..ConsoleMemoryCheck.MAX_BYTES &&
            maxReadSize in 1..ConsoleMemoryCheck.MAX_BYTES,
    ) {
      "Read request and transfer bounds must be one through 1048576 bytes"
    }
    require(failAfterBytes == null || failAfterBytes in 0..ConsoleMemoryCheck.MAX_BYTES) {
      "Bound the injected read-failure position through one MiB"
    }
    require(firstDescriptor in 3..31) { "First fixture descriptor must be between 3 and 31" }
    require(!missing || bytes.isEmpty() && failAfterBytes == null) {
      "A missing fixture has no contents or read-failure position"
    }
  }

  val byteLength: Int
    get() = bytes.length / 2 + additionalFiles.values.sumOf { it.length / 2 }

  internal fun decoded(): Map<String, ByteArray> =
      (additionalFiles + if (missing) emptyMap() else mapOf(path to bytes)).mapValues { (_, hex) ->
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
      }
}

/**
 * Observe each returned call separately so a later continuation cannot hide a leaked descriptor.
 */
internal class FunctionFileReadObservation(
    execution: AssemblyCase,
    private val definition: FunctionFileReadCheck,
) {
  private val input = definition.decoded()
  private val files =
      execution.provideFiles(
          input,
          maxReadSize = definition.maxReadSize,
          failAfterBytes = definition.failAfterBytes,
          firstDescriptor = definition.firstDescriptor,
      )
  private var observed = 0

  fun expectReturned(readFile: Boolean, invocation: FunctionFileReadInvocation? = null) {
    files.expectAllClosed()
    val history = files.snapshot()
    val events = history.subList(observed, history.size)
    if (!readFile) {
      requireCase(events.isEmpty()) { "This invocation must perform no file operations" }
    } else {
      expectRead(
          events,
          invocation ?: FunctionFileReadInvocation(definition.path, definition.maxRequest),
      )
    }
    input.forEach { (path, bytes) -> files.expectFile(path, bytes) }
    observed = history.size
  }

  private fun expectRead(events: List<FileAccess>, invocation: FunctionFileReadInvocation) {
    val contents = input[invocation.path]
    val opens = events.filter { it.operation == Operation.OPEN }
    requireCase(
        opens.size == 1 && opens.single().path == invocation.path && opens.single().flags == 0,
    ) {
      "Open the supplied fixture path once and read-only"
    }
    val open = opens.single()
    if (contents == null) {
      requireCase(events.size == 1 && open.result == -1 && open.descriptor == -1) {
        "A failed open creates no descriptor and permits no reads or closes"
      }
      return
    }
    val reads = events.filter { it.operation == Operation.READ }
    val closes = events.filter { it.operation == Operation.CLOSE }
    requireCase(
        open.descriptor >= 3 &&
            open.result == open.descriptor &&
            closes.size == 1 &&
            closes.single().descriptor == open.descriptor &&
            closes.single().result == 0,
    ) {
      "Close the acquired fixture descriptor exactly once before returning"
    }
    requireCase(
        events.first().operation == Operation.OPEN &&
            events.last().operation == Operation.CLOSE &&
            events.drop(1).dropLast(1).all { it.operation == Operation.READ },
    ) {
      "All reads must occur between the one open and close"
    }
    val error = definition.failAfterBytes?.let { it <= contents.size } == true
    val accepted = if (error) checkNotNull(definition.failAfterBytes) else contents.size
    // An injected read failure terminates with -1, including a failure exactly at EOF.
    requireCase(reads.isNotEmpty() && reads.last().result == if (error) -1 else 0) {
      "Read through the declared ${if (error) "failure" else "EOF"}; they are different outcomes"
    }
    requireCase(
        reads.all {
          it.descriptor == open.descriptor &&
              it.requestedBytes in 1..invocation.maxRequest &&
              it.result <= minOf(it.requestedBytes, definition.maxReadSize)
        },
    ) {
      "Use positive bounded read requests on the acquired descriptor"
    }
    requireCase(
        reads.dropLast(1).all { it.result > 0 } &&
            reads.sumOf { maxOf(0, it.result).toLong() } == accepted.toLong(),
    ) {
      "Consume the complete available prefix before its terminal read"
    }
  }
}
