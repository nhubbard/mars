package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
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

class FileOutputCheckTest {
  @Test
  fun `output is explicit copied binary data with append truncate and readback`() {
    val initial = byteArrayOf(9, 8)
    val paths = mutableSetOf("out")
    val files = GradingFiles()
    val check =
        files.configure(mapOf("out" to initial), 2, null, writable = paths, maximumWrite = 2)
    initial.fill(0)
    paths.clear()
    assertTrue(files.writesEnabled)
    assertEquals(-1, files.open("undeclared", 1))
    assertEquals(-1, files.open("undeclared", 9))
    assertEquals(-1, files.open(null, 1))
    assertEquals(-1, files.open("out", 2))
    val fd = files.open("out", 9)
    assertEquals(3, fd)
    assertEquals(-1, files.open("out", 1))
    assertEquals(-1, files.read(fd, ByteArray(1), 1))
    assertEquals(2, files.write(fd, byteArrayOf(0, -128, -1), 3))
    assertEquals(1, files.write(fd, byteArrayOf(-1), 1))
    check.expectFile("out", byteArrayOf(9, 8, 0, -128, -1))
    val detached = check.readFile("out")
    detached.fill(42)
    val history = check.snapshot()
    files.close(fd)
    check.expectAllClosed()
    val reader = files.open("out", 0)
    val buffer = ByteArray(8) { 42 }
    assertEquals(-1, files.write(reader, byteArrayOf(3), 1))
    assertEquals(2, files.read(reader, buffer, 8))
    assertArrayEquals(byteArrayOf(9, 8, 42, 42, 42, 42, 42, 42), buffer)
    files.close(reader)
    val writer = files.open("out", 1)
    check.expectFile("out", byteArrayOf())
    assertEquals(1, files.write(writer, byteArrayOf(77), 1))
    files.close(writer)
    check.expectAllClosed()
    check.expectFile("out", byteArrayOf(77))
    assertTrue(history.size < check.snapshot().size)
    assertThrows<CaseFailure> { check.expectFile("out", byteArrayOf(78)) }
    assertThrows<CaseFailure> { check.readFile("missing") }
  }

  @Test
  fun `short writes failures and file limits have exact prefixes and independent handles`() {
    for (failure in listOf(0, 2, 4)) {
      val files = GradingFiles()
      val check =
          files.configure(
              mapOf("a" to byteArrayOf(99)),
              1,
              null,
              writable = setOf("a", "b"),
              maximumWrite = 3,
              writeFailurePosition = failure,
          )
      val a = files.open("a", 9)
      val b = files.open("b", 1)
      assertEquals(0, files.write(a, byteArrayOf(), 0))
      var written = 0
      while (written < failure) {
        val count = files.write(a, byteArrayOf(0, -1, 7, 8), 4)
        assertEquals(minOf(3, failure - written), count)
        written += count
      }
      assertEquals(-1, files.write(a, byteArrayOf(1), 1))
      assertEquals(0, files.write(a, byteArrayOf(), 0))
      assertEquals(if (failure == 0) -1 else 1, files.write(b, byteArrayOf(5), 1))
      assertEquals(1 + failure, check.readFile("a").size)
      files.close(a)
      files.close(b)
      check.expectAllClosed()
      // Failure accounting starts again on a new open, rather than using file length.
      val reopened = files.open("a", 9)
      assertEquals(if (failure == 0) -1 else 1, files.write(reopened, byteArrayOf(6), 1))
      files.close(reopened)
    }
    val files = GradingFiles()
    val check = files.configure(emptyMap(), 1, null, writable = setOf("out"), maximumFileSize = 3)
    assertEquals(-1, files.open("out", 0))
    val fd = files.open("out", 1)
    assertEquals(3, files.write(fd, byteArrayOf(1, 2, 3, 4), 4))
    assertEquals(-1, files.write(fd, byteArrayOf(5), 1))
    assertEquals(0, files.write(fd, byteArrayOf(), 0))
    check.expectFile("out", byteArrayOf(1, 2, 3))
    files.close(fd)
    assertEquals(
        listOf(3, -1, 0),
        check.snapshot().filter { it.operation == Operation.WRITE }.map { it.result },
    )
  }

  @Test
  fun `zero progress preserves exact prefixes and resets per descriptor and open`() {
    for (threshold in listOf(0, 2, 4)) {
      val files = GradingFiles()
      val check =
          files.configure(
              mapOf("a" to byteArrayOf(99)),
              1,
              null,
              writable = setOf("a", "b"),
              maximumWrite = 3,
              zeroWritePosition = threshold,
          )
      val a = files.open("a", 9)
      val b = files.open("b", 1)
      var accepted = 0
      val expected = mutableListOf<Byte>(99)
      while (accepted < threshold) {
        val count = files.write(a, byteArrayOf(1, 2, 3, 4), 4)
        assertEquals(minOf(3, threshold - accepted), count)
        expected.addAll(byteArrayOf(1, 2, 3, 4).take(count))
        accepted += count
      }
      repeat(2) { assertEquals(0, files.write(a, byteArrayOf(7), 1)) }
      assertEquals(0, files.write(a, byteArrayOf(), 0))
      assertEquals(if (threshold == 0) 0 else 1, files.write(b, byteArrayOf(8), 1))
      check.expectFile("a", expected.toByteArray())
      val history = check.snapshot()
      assertEquals(
          listOf(0, 0),
          history
              .filter {
                it.operation == Operation.WRITE && it.descriptor == a && it.requestedBytes == 1
              }
              .map { it.result },
      )
      files.close(a)
      files.close(b)
      check.expectAllClosed()
      val reopened = files.open("a", 9)
      assertEquals(if (threshold == 0) 0 else 1, files.write(reopened, byteArrayOf(9), 1))
      files.close(reopened)
      check.expectAllClosed()
      assertTrue(history.size < check.snapshot().size)
    }
  }

  @Test
  fun `invalid writes and errors take precedence over zero progress`() {
    for (options in listOf("failure", "size", "invalid")) {
      val files = GradingFiles()
      val check =
          files.configure(
              emptyMap(),
              1,
              null,
              writable = setOf("out"),
              zeroWritePosition = if (options == "size") 1 else 0,
              writeFailurePosition = if (options == "failure") 0 else null,
              maximumFileSize = 1,
          )
      val fd = files.open("out", 1)
      if (options == "size") assertEquals(1, files.write(fd, byteArrayOf(9), 1))
      if (options == "invalid") {
        assertEquals(-1, files.write(fd, null, 0))
        assertEquals(-1, files.write(fd, byteArrayOf(9), 2))
        assertEquals(-1, files.write(-1, byteArrayOf(9), 1))
      } else assertEquals(-1, files.write(fd, byteArrayOf(9), 1))
      assertEquals(0, files.write(fd, byteArrayOf(), 0))
      files.close(fd)
      check.expectAllClosed()
    }
    val files = GradingFiles()
    assertThrows<IllegalArgumentException> {
      files.configure(emptyMap(), 1, null, zeroWritePosition = -1)
    }
    assertFalse(files.enabled)
    files.configure(emptyMap(), 1, null, zeroWritePosition = 0)
    assertTrue(files.enabled)
  }

  @Test
  fun `invalid writes do not change files or enable undeclared host access`() {
    val files = GradingFiles()
    val check = files.configure(mapOf("input" to byteArrayOf(7)), 1, null, writable = setOf("out"))
    val input = files.open("input", 0)
    val output = files.open("out", 1)
    for (fd in listOf(-1, 0, 1, 2, 31, input)) assertEquals(-1, files.write(fd, byteArrayOf(9), 1))
    assertEquals(-1, files.write(output, null, 0))
    assertEquals(-1, files.write(output, byteArrayOf(9), -1))
    assertEquals(-1, files.write(output, byteArrayOf(9), 2))
    check.expectFile("input", byteArrayOf(7))
    check.expectFile("out", byteArrayOf())
    files.close(output)
    assertEquals(-1, files.write(output, byteArrayOf(1), 1))
    files.close(input)
    check.expectAllClosed()
    val readOnly = GradingFiles()
    readOnly.configure(emptyMap(), 1, null)
    assertFalse(readOnly.writesEnabled)
    assertEquals(-1, readOnly.open("out", 1))
  }

  @Test
  fun `exhausted opens do not truncate and append can create a new fixture`() {
    val files = GradingFiles()
    val check =
        files.configure(
            mapOf("in" to byteArrayOf(7), "out" to byteArrayOf(9)),
            1,
            null,
            minimumDescriptor = 31,
            writable = setOf("out", "new"),
        )
    assertEquals(31, files.open("in", 0))
    assertEquals(-1, files.open("out", 1))
    assertEquals(-1, files.open("new", 9))
    check.expectFile("out", byteArrayOf(9))
    assertThrows<CaseFailure> { check.readFile("new") }
    files.close(31)
    assertEquals(31, files.open("new", 9))
    assertEquals(1, files.write(31, byteArrayOf(0), 1))
    files.close(31)
    check.expectAllClosed()
    check.expectFile("new", byteArrayOf(0))
  }

  @Test
  fun `invalid output options leave configuration available`() {
    for (paths in listOf(setOf(""), setOf(" "), setOf("bad\u0000path"))) {
      val files = GradingFiles()
      assertThrows<IllegalArgumentException> {
        files.configure(emptyMap(), 1, null, writable = paths)
      }
      assertFalse(files.enabled)
    }
    val files = GradingFiles()
    for (limit in listOf(0, -1)) {
      assertThrows<IllegalArgumentException> {
        files.configure(emptyMap(), 1, null, maximumWrite = limit)
      }
      assertThrows<IllegalArgumentException> {
        files.configure(emptyMap(), 1, null, maximumFileSize = limit)
      }
    }
    assertThrows<IllegalArgumentException> {
      files.configure(emptyMap(), 1, null, writeFailurePosition = -1)
    }
    assertFalse(files.enabled)
    files.configure(emptyMap(), 1, null, writable = setOf("valid"))
    assertTrue(files.writesEnabled)
  }

  @Test
  fun `actual wide-address syscalls capture binary output without touching host files`(
      @TempDir directory: Path,
  ) {
    val hostFile = directory.resolve("output.ppm").also { it.writeText("host stays unchanged") }
    val source = directory.resolve("output.asm")
    val text =
        $$"""
            .data 0x100000000
        path: .asciiz "$$hostFile"
        data: .byte 0,128,255,10,42
            .text 0x100400000
        main:
            dla $a0, path
            li $a1, 1
            li $v0, 13
            syscall
        opened:
            move $s0, $v0
            move $a0, $s0
            dla $a1, data
            li $a2, 4
            li $v0, 15
            syscall
        written:
            move $a0, $s0
            li $v0, 16
            syscall
        closed:
            li $v0, 10
            syscall
        """
            .trimIndent()
    val assignment = Assignment(AssemblySourceSnapshot(listOf(source), mapOf(source to text)))
    assignment.case("binary short write") {
      val files =
          provideFiles(
              emptyMap(),
              writablePaths = setOf(hostFile.toString()),
              maxWriteSize = 2,
              firstDescriptor = 9,
          )
      startAt("main")
      runUntil("opened")
      expectRegister("v0", 9)
      val reads = traceMemoryAccesses(readDataAddress("data"), 5)
      runUntil("written")
      expectRegister("v0", 2)
      reads.close()
      files.expectFile(hostFile.toString(), byteArrayOf(0, -128))
      // The existing educational syscall reads the requested four bytes plus a guard byte,
      // even when the environment accepts only a short prefix. Do not hide that footprint.
      assertEquals((0L..4L).toList(), reads.snapshot().map { it.address - readDataAddress("data") })
      runUntil("closed")
      files.expectAllClosed()
      runToExit()
      expectOutput("")
      assertEquals(
          listOf(Operation.OPEN, Operation.WRITE, Operation.CLOSE),
          files.snapshot().map { it.operation },
      )
    }
    assignment.case("injected failure and exact declared path") {
      val files =
          provideFiles(
              emptyMap(),
              writablePaths = setOf(hostFile.toString()),
              failWriteAfterBytes = 0,
          )
      startAt("main")
      runUntil("written")
      expectRegister("v0", -1)
      files.expectFile(hostFile.toString(), byteArrayOf())
      runUntil("closed")
      files.expectAllClosed()
      runToExit()
    }
    assignment.case("zero progress through the public DSL and syscall") {
      val files =
          provideFiles(
              emptyMap(),
              writablePaths = setOf(hostFile.toString()),
              zeroWriteAfterBytes = 0,
          )
      startAt("main")
      runUntil("written")
      expectRegister("v0", 0)
      files.expectFile(hostFile.toString(), byteArrayOf())
      runUntil("closed")
      files.expectAllClosed()
      runToExit()
      assertEquals(
          listOf(0),
          files.snapshot().filter { it.operation == Operation.WRITE }.map { it.result },
      )
    }
    assignment.case("read-only configuration keeps writes disabled") {
      provideFiles(emptyMap())
      startAt("main")
      runUntil("opened")
      expectRegister("v0", -1)
      runToExit()
    }
    assertEquals(listOf(null, null, null), assignment.results.take(3).map { it.failure })
    assertTrue(assignment.results[3].failure.orEmpty().contains("syscall 15 is disabled"))
    assertEquals("host stays unchanged", hostFile.readText())
  }
}
