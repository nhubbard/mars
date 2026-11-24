package edu.missouristate.mars.cli.grading

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FunctionFileSystemDefinitionTest {
  private fun fixture() = FunctionFileSystemCheck(mapOf("in" to "0031", "out" to ""), setOf("out"))

  private fun call() =
      FunctionCallCheck(
          "function",
          "returned",
          emptyMap(),
          mapOf("v0" to 0L),
          expectedFiles = mapOf("out" to "0031"),
      )

  @Test
  fun `fixture sizes transfer bounds failures paths and histories are finite`() {
    for (path in listOf("", "bad\u0000path", "\ud800")) {
      assertThrows<IllegalArgumentException> { fixture().copy(files = mapOf(path to "00")) }
      assertThrows<IllegalArgumentException> { fixture().copy(writablePaths = setOf(path)) }
    }
    for (bytes in listOf("0", "gg", "000", "00".repeat(1_048_577))) assertThrows<
        IllegalArgumentException,
    > {
      fixture().copy(files = mapOf("in" to bytes))
    }
    assertThrows<IllegalArgumentException> {
      fixture().copy(files = mapOf("a" to "00".repeat(600_000), "b" to "00".repeat(600_000)))
    }
    assertThrows<IllegalArgumentException> {
      fixture().copy(writablePaths = (0..64).map { "file$it" }.toSet())
    }
    for (n in listOf(0, 1_048_577)) {
      assertThrows<IllegalArgumentException> { fixture().copy(maxReadSize = n) }
      assertThrows<IllegalArgumentException> { fixture().copy(maxWriteSize = n) }
      assertThrows<IllegalArgumentException> { fixture().copy(maxFileSize = n) }
    }
    for (n in listOf(-1, 1_048_577)) {
      assertThrows<IllegalArgumentException> { fixture().copy(failReadAfterBytes = n) }
      assertThrows<IllegalArgumentException> { fixture().copy(failWriteAfterBytes = n) }
      assertThrows<IllegalArgumentException> { fixture().copy(zeroWriteAfterBytes = n) }
    }
    for (n in listOf(2, 32)) assertThrows<IllegalArgumentException> {
      fixture().copy(firstDescriptor = n)
    }
    assertEquals(2, fixture().byteLength)
    assertEquals(0, FunctionFileSystemCheck(emptyMap()).byteLength)
  }

  @Test
  fun `each writable result is explicit and expected absence is distinct from empty bytes`() {
    FunctionCheckCase("files", listOf(call()), fileSystem = fixture())
    FunctionCheckCase(
        "absent",
        listOf(call().copy(expectedFiles = mapOf("out" to null))),
        fileSystem = fixture(),
    )
    assertThrows<IllegalArgumentException> { FunctionCheckCase("missing fixture", listOf(call())) }
    assertThrows<IllegalArgumentException> {
      FunctionCheckCase(
          "unused fixture",
          listOf(call().copy(expectedFiles = null)),
          fileSystem = fixture(),
      )
    }
    assertThrows<IllegalArgumentException> {
      FunctionCheckCase(
          "missing path",
          listOf(call().copy(expectedFiles = emptyMap())),
          fileSystem = fixture(),
      )
    }
    assertThrows<IllegalArgumentException> {
      FunctionCheckCase(
          "extra path",
          listOf(call().copy(expectedFiles = mapOf("in" to "00", "out" to "00"))),
          fileSystem = fixture(),
      )
    }
    assertThrows<IllegalArgumentException> {
      FunctionCheckCase(
          "two environments",
          listOf(call()),
          fileSystem = fixture(),
          fileRead = FunctionFileReadCheck("in", "00", 1),
      )
    }
    assertThrows<IllegalArgumentException> { call().copy(expectedFiles = mapOf("out" to "x")) }
    assertThrows<IllegalArgumentException> { call().copy(expectedFiles = mapOf("" to null)) }
    assertThrows<IllegalArgumentException> {
      call().copy(expectedFiles = (0..64).associate { "file$it" to "00" })
    }
    assertThrows<IllegalArgumentException> {
      call().copy(expectedFiles = mapOf("a" to "00".repeat(600_000), "b" to "00".repeat(600_000)))
    }
    val max = fixture().copy(files = mapOf("in" to "00".repeat(1_048_576)))
    assertThrows<IllegalArgumentException> {
      FunctionCheckCase("combined bytes", listOf(call()), fileSystem = max)
    }
  }
}
