package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PathWriteFailureTest {
    @Test
    fun `path failure overrides global limit without affecting another output`() {
        val failures = mutableMapOf("stream" to 3)
        val files = GradingFiles()
        val check = files.configure(emptyMap(), 1, null, writable = setOf("stream", "pixels"),
            maximumWrite = 2, writeFailurePosition = 1, writeFailuresByPath = failures)
        failures["stream"] = 0
        val stream = files.open("stream", 1)
        val pixels = files.open("pixels", 1)
        val bytes = byteArrayOf(7, 8, 9, 10)
        assertEquals(2, files.write(stream, bytes, 4))
        assertEquals(1, files.write(stream, bytes, 4))
        assertEquals(-1, files.write(stream, bytes, 1))
        assertEquals(0, files.write(stream, bytes, 0))
        assertEquals(1, files.write(pixels, bytes, 4))
        assertEquals(-1, files.write(pixels, bytes, 1))
        files.close(stream); files.close(pixels)
        check.expectAllClosed()
        check.expectFile("stream", byteArrayOf(7, 8, 7))
        check.expectFile("pixels", byteArrayOf(7))
    }

    @Test
    fun `targeted failure leaves an unconfigured output unlimited and resets per open`() {
        val files = GradingFiles()
        val check = files.configure(mapOf("a" to byteArrayOf(99)), 1, null,
            writable = setOf("a", "b"), writeFailuresByPath = mapOf("a" to 2))
        val a = files.open("a", 9)
        val b = files.open("b", 1)
        assertEquals(2, files.write(a, byteArrayOf(1, 2, 3), 3))
        assertEquals(-1, files.write(a, byteArrayOf(3), 1))
        assertEquals(3, files.write(b, byteArrayOf(4, 5, 6), 3))
        files.close(a); files.close(b)
        val reopened = files.open("a", 9)
        assertEquals(2, files.write(reopened, byteArrayOf(7, 8, 9), 3))
        files.close(reopened)
        check.expectAllClosed()
        check.expectFile("a", byteArrayOf(99, 1, 2, 7, 8))
        check.expectFile("b", byteArrayOf(4, 5, 6))
    }

    @Test
    fun `zero path failure permits empty writes and file size still bounds other paths`() {
        val files = GradingFiles()
        val check = files.configure(emptyMap(), 1, null, writable = setOf("a", "b"),
            maximumFileSize = 2, writeFailuresByPath = mapOf("a" to 0, "b" to 10))
        val a = files.open("a", 1)
        val b = files.open("b", 1)
        assertEquals(0, files.write(a, byteArrayOf(), 0))
        assertEquals(-1, files.write(a, byteArrayOf(1), 1))
        assertEquals(2, files.write(b, byteArrayOf(2, 3, 4), 3))
        assertEquals(-1, files.write(b, byteArrayOf(4), 1))
        files.close(a); files.close(b)
        check.expectAllClosed()
    }

    @Test
    fun `invalid failure maps reject before configuration becomes enabled`() {
        val files = GradingFiles()
        for (map in listOf(mapOf("a" to -1), mapOf("missing" to 0), mapOf("" to 1))) {
            assertThrows<IllegalArgumentException> {
                files.configure(emptyMap(), 1, null, writable = setOf("a"), writeFailuresByPath = map)
            }
            assertFalse(files.enabled)
        }
        files.configure(emptyMap(), 1, null, writable = setOf("a"), writeFailuresByPath = mapOf("a" to 0))
        assertTrue(files.enabled)
    }

    @Test
    fun `public assignment option reaches actual syscalls for each declared path`(@TempDir directory: Path) {
        val source = directory.resolve("targeted.asm")
        val text = $$"""
            .data 0x100000000
        a: .asciiz "a"
        b: .asciiz "b"
        bytes: .byte 1,2,3,0
            .text 0x100400000
        main:
            dla $a0, a
            li $a1, 1
            li $v0, 13
            syscall
            move $s0, $v0
            move $a0, $s0
            dla $a1, bytes
            li $a2, 3
            li $v0, 15
            syscall
        wrote_a:
            move $a0, $s0
            li $v0, 16
            syscall
            dla $a0, b
            li $a1, 1
            li $v0, 13
            syscall
            move $s0, $v0
            move $a0, $s0
            dla $a1, bytes
            li $a2, 3
            li $v0, 15
            syscall
        wrote_b:
            move $a0, $s0
            li $v0, 16
            syscall
            li $v0, 10
            syscall
        """.trimIndent()
        val assignment = Assignment(AssemblySourceSnapshot(listOf(source), mapOf(source to text)))
        for (global in listOf(null, 1)) assignment.case("path override and global $global") {
            val files = provideFiles(emptyMap(), writablePaths = setOf("a", "b"),
                failWriteAfterBytes = global, failWriteAfterBytesByPath = mapOf("a" to 2))
            startAt("main"); runUntil("wrote_a"); expectRegister("v0", 2)
            runUntil("wrote_b"); expectRegister("v0", global ?: 3)
            runToExit(); files.expectAllClosed()
            files.expectFile("a", byteArrayOf(1, 2))
            files.expectFile("b", if (global == null) byteArrayOf(1, 2, 3) else byteArrayOf(1))
        }
        assertEquals(listOf(null, null), assignment.results.map { it.failure })
    }
}
