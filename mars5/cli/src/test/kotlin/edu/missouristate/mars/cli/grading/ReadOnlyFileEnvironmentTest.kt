package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.api.SyscallEnvironment
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReadOnlyFileEnvironmentTest {
  @Test
  fun `interactive file engine retains no operation history during repeated EOF reads`() {
    val files = GradingFiles(recordAccesses = false)
    files.configure(mapOf("input" to byteArrayOf(99)), 1, null)
    val fd = files.open("input", 0)
    val buffer = ByteArray(1)
    assertEquals(1, files.read(fd, buffer, 1))
    assertEquals(99, buffer[0].toInt())
    repeat(10_000) { assertEquals(0, files.read(fd, buffer, 1)) }
    files.close(fd)
    assertTrue(files.snapshot().isEmpty())
    assertEquals(0, files.openCount)
  }

  private fun withIo(action: (SyscallEnvironment, ByteArrayOutputStream) -> Unit) {
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { session ->
          val bytes = ByteArrayOutputStream()
          PrintStream(bytes).use { out ->
            SessionSyscallEnvironment(
                    session.memory,
                    ByteArrayInputStream("42\n".toByteArray()),
                    out,
                    out,
                )
                .use {
                  action(it, bytes)
                }
          }
        }
  }

  @Test
  fun `multiple read-only inputs are captured once and reopened with independent cursors`() =
      withIo { io, _ ->
        val extra = mutableMapOf("other" to "80ff")
        val definition = FunctionFileReadCheck("input", "01", 7, additionalFiles = extra)
        ReadOnlyFileEnvironment(io, definition).use { files ->
          extra["other"] = "00"
          val buffer = ByteArray(4)
          val first = files.openFile("input", 0)
          assertEquals(1, files.readFromFile(first, buffer, 4))
          assertEquals(1, buffer[0].toInt())
          files.closeFile(first)
          repeat(2) {
            val second = files.openFile("other", 0)
            assertEquals(2, files.readFromFile(second, buffer, 4))
            assertEquals(-128, buffer[0].toInt())
            assertEquals(-1, buffer[1].toInt())
            assertEquals(0, files.readFromFile(second, buffer, 4))
            files.closeFile(second)
          }
          assertEquals(-1, files.openFile("missing", 0))
        }
        extra["other"] = "bad hex"
        assertThrows<IllegalArgumentException> { ReadOnlyFileEnvironment(io, definition) }
      }

  @Test
  fun `short transfers descriptor reuse EOF and console delegation are independent of cleanup`() =
      withIo { io, output ->
        val definition =
            FunctionFileReadCheck("input", "0080ff01", 1, maxReadSize = 3, firstDescriptor = 19)
        val first = ReadOnlyFileEnvironment(io, definition)
        ReadOnlyFileEnvironment(io, definition).use { second ->
          val buffer = ByteArray(4) { 77 }
          assertEquals(19, first.openFile("input", 0))
          assertEquals(-1, first.openFile("input", 0))
          assertEquals(19, second.openFile("input", 0))
          assertEquals(3, first.readFromFile(19, buffer, 4))
          assertArrayEquals(byteArrayOf(0, -128, -1, 77), buffer)
          assertEquals(1, first.readFromFile(19, buffer, 4))
          assertEquals(0, first.readFromFile(19, buffer, 4))
          first.closeFile(19)
          assertEquals(19, first.openFile("input", 0))
          assertEquals(42, first.readInt())
          first.printString("lesson ")
          first.close()
          first.close()
          assertThrows<IllegalStateException> { first.openFile("input", 0) }
          assertEquals(3, second.readFromFile(19, buffer, 4))
        }
        io.printString("console survives")
        assertEquals("lesson console survives", output.toString())
      }

  @Test
  fun `file operations never reach the delegate including invalid or write requests`() =
      withIo { io, _ ->
        val guarded =
            object : SyscallEnvironment by io {
              override fun allowsSyscall(number: Int) = number == 34

              override fun openFile(path: String?, flags: Int): Int = error("host open")

              override fun readFromFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int =
                  error("host read")

              override fun writeToFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int =
                  error("host write")

              override fun closeFile(fd: Int): Unit = error("host close")
            }
        ReadOnlyFileEnvironment(guarded, FunctionFileReadCheck("input", "", 7)).use { files ->
          for (number in listOf(13, 14, 16, 34)) assertTrue(files.allowsSyscall(number))
          for (number in listOf(1, 15, 30)) assertFalse(files.allowsSyscall(number))
          for (flags in listOf(1, 9, -1)) assertEquals(-1, files.openFile("input", flags))
          assertEquals(-1, files.openFile("/etc/passwd", 0))
          assertEquals(-1, files.openFile(null, 0))
          assertEquals(-1, files.readFromFile(3, null, 1))
          assertEquals(-1, files.writeToFile(3, byteArrayOf(1), 1))
          files.closeFile(3)
          assertEquals(3, files.openFile("input", 0))
          assertEquals(0, files.readFromFile(3, ByteArray(1), 1))
          assertEquals(-1, files.writeToFile(3, byteArrayOf(1), 1))
          files.closeFile(3)
        }
      }

  @Test
  fun `failure boundary and missing input use identical grader semantics`() = withIo { io, _ ->
    ReadOnlyFileEnvironment(io, FunctionFileReadCheck("input", "313233343536373839", 7, 3, 5, 19))
        .use { files ->
          assertEquals(19, files.openFile("input", 0))
          val buffer = ByteArray(7)
          assertEquals(3, files.readFromFile(19, buffer, 7))
          assertEquals(2, files.readFromFile(19, buffer, 7))
          assertEquals(-1, files.readFromFile(19, buffer, 7))
          files.closeFile(19)
        }
    ReadOnlyFileEnvironment(io, FunctionFileReadCheck("missing", "", 7, missing = true)).use { files
      ->
      assertEquals(-1, files.openFile("missing", 0))
    }
  }
}
