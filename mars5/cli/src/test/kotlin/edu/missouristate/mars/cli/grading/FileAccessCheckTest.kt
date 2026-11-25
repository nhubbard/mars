package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.assemblyStringLiteral
import edu.missouristate.mars.cli.grading.FileAccess.Operation
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class FileAccessCheckTest {
  @Test
  fun `fixture descriptor selection validates its range and retains exhaustion and reuse`() {
    val files = GradingFiles()
    for (first in listOf(-1, 0, 2, 32, Int.MAX_VALUE)) {
      assertThrows<IllegalArgumentException> { files.configure(emptyMap(), 1, null, first) }
      assertFalse(files.enabled)
    }
    val check = files.configure(mapOf("a" to byteArrayOf(7), "b" to byteArrayOf(9)), 1, null, 31)
    assertEquals(31, files.open("a", 0))
    assertEquals(-1, files.open("b", 0))
    assertEquals(-1, files.read(3, ByteArray(1), 1))
    files.close(31)
    assertEquals(31, files.open("b", 0))
    val bytes = ByteArray(1)
    assertEquals(1, files.read(31, bytes, 1))
    assertEquals(9, bytes.single().toInt())
    files.close(31)
    check.expectAllClosed()
  }

  @Test
  fun `copied binary fixtures support short reads EOF descriptor reuse and detached history`() {
    val files = GradingFiles()
    val bytes = byteArrayOf(0, -128, -1, 10)
    val check = files.configure(mapOf("binary" to bytes), 2, null)
    bytes.fill(99)
    val descriptor = files.open("binary", 0)
    assertEquals(3, descriptor)
    val early = check.snapshot()
    assertThrows<CaseFailure> { check.expectAllClosed() }
    val destination = ByteArray(5) { 42 }
    assertEquals(2, files.read(descriptor, destination, 4))
    assertArrayEquals(byteArrayOf(0, -128, 42, 42, 42), destination)
    assertEquals(2, files.read(descriptor, destination, 4))
    assertArrayEquals(byteArrayOf(-1, 10, 42, 42, 42), destination)
    assertEquals(0, files.read(descriptor, destination, 4))
    files.close(descriptor)
    check.expectAllClosed()
    assertEquals(1, early.size)
    assertEquals(3, files.open("binary", 0))
    assertEquals(1, files.read(3, destination, 1))
    assertEquals(0, destination[0].toInt())
    files.close(3)
    assertEquals(
        listOf(2, 2, 0, 1),
        check.snapshot().filter { it.operation == Operation.READ }.map { it.result },
    )
  }

  @Test
  fun `missing paths invalid modes and invalid descriptors never fall back to host services`() {
    val files = GradingFiles()
    val check = files.configure(mapOf("valid" to byteArrayOf(1)), 1, null)
    for (path in listOf(null, "missing", "./valid", "../valid")) assertEquals(
        -1,
        files.open(path, 0),
    )
    for (mode in listOf(1, 9, -1)) assertEquals(-1, files.open("valid", mode))
    val buffer = ByteArray(1)
    for (descriptor in listOf(-1, 0, 1, 2, 32)) assertEquals(-1, files.read(descriptor, buffer, 1))
    files.close(3)
    assertEquals(-1, check.snapshot().last().result)
    assertEquals(3, files.open("valid", 0))
    assertEquals(-1, files.open("valid", 0))
    assertEquals(-1, files.read(3, null, 0))
    assertEquals(-1, files.read(3, buffer, -1))
    assertEquals(-1, files.read(3, buffer, 2))
    assertEquals(0, files.read(3, buffer, 0))
    assertEquals(1, files.read(3, buffer, 1))
    files.close(3)
    assertEquals(-1, files.read(3, buffer, 1))
    check.expectAllClosed()
  }

  @Test
  fun `descriptor exhaustion is recoverable and positions belong to individual opens`() {
    val files = GradingFiles()
    files.configure((0..29).associate { "$it" to byteArrayOf(it.toByte()) }, 1, null)
    for (index in 0..28) assertEquals(index + 3, files.open("$index", 0))
    assertEquals(-1, files.open("29", 0))
    files.close(11)
    assertEquals(11, files.open("29", 0))
    val buffer = ByteArray(1)
    assertEquals(1, files.read(11, buffer, 1))
    assertEquals(29, buffer[0].toInt())
    assertEquals(1, files.read(3, buffer, 1))
    assertEquals(0, buffer[0].toInt())
  }

  @Test
  fun `injected errors stop a preceding read at the failure position and do not become EOF`() {
    for (failure in listOf(0, 2, 4)) {
      val files = GradingFiles()
      files.configure(mapOf("input" to byteArrayOf(1, 2, 3, 4)), 4, failure)
      files.open("input", 0)
      val buffer = ByteArray(4)
      assertEquals(0, files.read(3, buffer, 0))
      if (failure > 0) assertEquals(failure, files.read(3, buffer, 4))
      assertEquals(-1, files.read(3, buffer, 4))
      assertEquals(0, files.read(3, buffer, 0))
    }
    val files = GradingFiles()
    files.configure(mapOf("empty" to byteArrayOf()), 1, 1)
    files.open("empty", 0)
    assertEquals(0, files.read(3, ByteArray(1), 1))
  }

  @Test
  fun `configuration validates before enabling and cannot replace an active fixture set`() {
    val files = GradingFiles()
    assertFalse(files.enabled)
    for (maximum in listOf(0, -1)) assertThrows<IllegalArgumentException> {
      files.configure(emptyMap(), maximum, null)
    }
    assertThrows<IllegalArgumentException> { files.configure(emptyMap(), 1, -1) }
    for (path in listOf("", " ", "bad\u0000path")) assertThrows<IllegalArgumentException> {
      files.configure(mapOf(path to byteArrayOf()), 1, null)
    }
    assertFalse(files.enabled)
    val check = files.configure(emptyMap(), 1, null)
    assertTrue(files.enabled)
    check.expectAllClosed()
    assertThrows<CaseFailure> { files.configure(emptyMap(), 1, null) }
  }

  @Test
  fun `actual syscalls use wide addresses fixtures and case-local descriptor histories`(
      @TempDir directory: Path,
  ) {
    val hostFile =
        directory.resolve("host.bin").also { it.writeText("host bytes must remain untouched") }
    val source = directory.resolve("files.asm")
    val text =
        $$"""
            .data 0x100000000
        path: .asciiz $${assemblyStringLiteral(hostFile.toString())}
        buffer: .byte 42:8
            .text
        main:
            dla $a0, path
            li $a1, 0
            li $v0, 13
            syscall
        opened:
            move $s0, $v0
            move $a0, $s0
            dla $a1, buffer
            li $a2, 4
            li $v0, 14
            syscall
        read_once:
            move $a0, $s0
            li $v0, 14
            syscall
        read_twice:
            move $a0, $s0
            li $v0, 14
            syscall
        failed:
            move $a0, $s0
            li $v0, 16
            syscall
        closed:
            li $v0, 10
            syscall
        write_attempt:
            li $v0, 15
            syscall
        """
            .trimIndent()
    val assignment = Assignment(AssemblySourceSnapshot(listOf(source), mapOf(source to text)))
    assignment.case("binary reads and cleanup") {
      val files =
          provideFiles(
              mapOf(hostFile.toString() to byteArrayOf(-128, -1, 0, 9)),
              maxReadSize = 2,
              failAfterBytes = 3,
          )
      runUntil("opened")
      expectRegister("v0", 3)
      runUntil("read_once")
      expectRegister("v0", 2)
      expectBytes("buffer", 128, 255, 42, 42)
      runUntil("read_twice")
      expectRegister("v0", 1)
      expectBytes("buffer", 0, 255, 42, 42)
      runUntil("failed")
      expectRegister("v0", -1)
      assertThrows<CaseFailure> { files.expectAllClosed() }
      runUntil("closed")
      files.expectAllClosed()
      runToExit()
      expectOutput("")
      assertEquals(
          listOf(Operation.OPEN, Operation.READ, Operation.READ, Operation.READ, Operation.CLOSE),
          files.snapshot().map { it.operation },
      )
    }
    assignment.case("empty fixtures cannot open the existing host path") {
      val files = provideFiles(emptyMap())
      runUntil("opened")
      expectRegister("v0", -1)
      assertEquals(1, files.snapshot().size)
      files.expectAllClosed()
    }
    assignment.case("write remains disabled") {
      provideFiles(emptyMap())
      startAt("write_attempt")
      runToExit()
    }
    assignment.case("unconfigured case remains restricted") { runToExit() }
    assignment.case("configured descriptor is visible through the actual syscalls") {
      val files =
          provideFiles(mapOf(hostFile.toString() to byteArrayOf(1, 2, 3, 4)), firstDescriptor = 9)
      runUntil("opened")
      expectRegister("v0", 9)
      runUntil("closed")
      files.expectAllClosed()
      runToExit()
      expectOutput("")
      assertEquals(listOf(9, 9, 9, 9, 9), files.snapshot().map { it.descriptor })
      assertEquals(
          listOf(4, 0, 0),
          files.snapshot().filter { it.operation == Operation.READ }.map { it.result },
      )
    }
    assertEquals(listOf(null, null), assignment.results.take(2).map { it.failure })
    assertTrue(assignment.results[2].failure.orEmpty().contains("syscall 15 is disabled"))
    assertTrue(assignment.results[3].failure.orEmpty().contains("syscall 13 is disabled"))
    assertEquals(null, assignment.results[4].failure)
    assertEquals("host bytes must remain untouched", hostFile.readText())
  }
}
