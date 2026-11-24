package edu.missouristate.mars.cli.grading

/** Bounded virtual files for a complete call, including deterministic short or failed writes. */
data class FunctionFileSystemCheck(
    val files: Map<String, String>,
    val writablePaths: Set<String> = emptySet(),
    val maxReadSize: Int = ConsoleMemoryCheck.MAX_BYTES,
    val maxWriteSize: Int = ConsoleMemoryCheck.MAX_BYTES,
    val firstDescriptor: Int = 3,
    val failReadAfterBytes: Int? = null,
    val failWriteAfterBytes: Int? = null,
    val zeroWriteAfterBytes: Int? = null,
    val maxFileSize: Int = ConsoleMemoryCheck.MAX_BYTES,
) {
  init {
    require((files.keys + writablePaths).size <= 64) { "Declare at most 64 fixture file paths" }
    files.forEach { (path, bytes) ->
      fixturePath(path)
      fixtureBytes(bytes)
    }
    writablePaths.forEach(::fixturePath)
    require(byteLength <= ConsoleMemoryCheck.MAX_BYTES) { "Combined fixture files exceed one MiB" }
    require(
        maxReadSize in 1..ConsoleMemoryCheck.MAX_BYTES &&
            maxWriteSize in 1..ConsoleMemoryCheck.MAX_BYTES &&
            maxFileSize in 1..ConsoleMemoryCheck.MAX_BYTES,
    ) {
      "File transfer and output bounds must be one through 1048576 bytes"
    }
    require(firstDescriptor in 3..31) { "First fixture descriptor must be between 3 and 31" }
    for (position in listOf(failReadAfterBytes, failWriteAfterBytes, zeroWriteAfterBytes)) require(
        position == null || position in 0..ConsoleMemoryCheck.MAX_BYTES,
    ) {
      "Bound injected failure or zero-progress positions through one MiB"
    }
  }

  val byteLength: Int
    get() = files.values.sumOf { it.length / 2 }

  internal fun decoded() = files.mapValues { (_, hex) -> decodeFixtureBytes(hex) }
}

internal fun decodeFixtureBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun functionExpectedFiles(expected: Map<String, String?>?) {
  if (expected == null) return
  require(expected.size <= 64) { "Declare at most 64 expected fixture files" }
  expected.forEach { (path, bytes) ->
    fixturePath(path)
    if (bytes != null) fixtureBytes(bytes)
  }
  require(
      expected.values.sumOf { (it?.length ?: 0).toLong() / 2 } <= ConsoleMemoryCheck.MAX_BYTES,
  ) {
    "Expected fixture file bytes exceed one MiB"
  }
}

/**
 * One environment persists across invocations; each return checks closure and exact declared
 * outputs.
 */
internal class FunctionFileSystemObservation(
    execution: AssemblyCase,
    private val definition: FunctionFileSystemCheck,
) {
  private val original = definition.decoded()
  private val files =
      execution.provideFiles(
          original,
          maxReadSize = definition.maxReadSize,
          failAfterBytes = definition.failReadAfterBytes,
          firstDescriptor = definition.firstDescriptor,
          writablePaths = definition.writablePaths,
          maxWriteSize = definition.maxWriteSize,
          failWriteAfterBytes = definition.failWriteAfterBytes,
          zeroWriteAfterBytes = definition.zeroWriteAfterBytes,
          maxFileSize = definition.maxFileSize,
      )
  private var observed = 0L

  fun expectReturned(expected: Map<String, String?>?) {
    files.expectAllClosed()
    val operations = files.operationCount
    if (expected == null)
        requireCase(operations == observed) { "This invocation must perform no file operations" }
    else
        for ((path, hex) in expected) {
          if (hex == null) files.expectMissing(path)
          else files.expectFile(path, decodeFixtureBytes(hex))
        }
    for ((path, bytes) in original) if (path !in definition.writablePaths)
        files.expectFile(path, bytes)
    observed = operations
  }
}
