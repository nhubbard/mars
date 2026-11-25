package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class MemorySnapshotTest {
  @Test
  fun `snapshots are detached unsigned inspections with no access events or undo edits`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("snapshot.asm").also {
          it.writeText(
              $$"""
                  .data
              narrow: .word 0x80ff7f00, 0x11223344
                  .data 0x100000000
              wide: .word 0xfedcba98
                  .text
                  la $t0, narrow
                  li $t1, 0x1234
                  sw $t1, 0($t0)
              ready:
                  nop
                  li $v0, 10
                  syscall
              """
                  .trimIndent(),
          )
        }
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { machine ->
          val program = machine.assemble(listOf(source))
          AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
            assertEquals(0x80ff7f00.toInt(), case.readWord("narrow"))
            assertArrayEquals(intArrayOf(0, 127, 255, 128, 68, 51), case.readBytes("narrow", 6))
            assertArrayEquals(
                intArrayOf(127, 255, 128, 68),
                case.readBytes("narrow", 4, offset = 1),
            )
            assertEquals(0x10010000L, case.readDataAddress("narrow"))
            assertEquals(0x100000003L, case.readDataAddress("wide", offset = 3))
            assertEquals(0xfedcba98.toInt(), case.readWord("wide"))
            assertArrayEquals(intArrayOf(152, 186, 220, 254), case.readBytes("wide", 4))
            assertArrayEquals(intArrayOf(), case.readBytes("wide", 0))
            case.runUntil("ready")
            val accesses = mutableListOf<MemoryAccessNotice>()
            val observer: (MemoryAccessNotice) -> Unit = { accesses += it }
            machine.memory.addAddressObserver(observer, includeInstructionFetch = true)
            val bytes = case.readBytes("narrow", 4)
            val word = case.readWord("narrow")
            bytes[0] = 99
            assertArrayEquals(intArrayOf(52, 18, 0, 0), case.readBytes("narrow", 4))
            assertTrue(accesses.isEmpty())
            machine.memory.deleteAddressObserver(observer)
            assertEquals(0x1234, word)
            machine.simulator().backstepAsync().join()
            assertEquals(0x80ff7f00.toInt(), case.readWord("narrow"))
            assertEquals(0x1234, word)
            assertEquals(99, bytes[0])
            case.step()
            assertEquals(0x1234, case.readWord("narrow"))
          }
        }
  }

  @Test
  fun `snapshots validate label kind alignment mapped range and arguments`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("bounds.asm").also {
          it.writeText(
              ".data\nvalue: .word 0\n.data 0x100000000\nwide: .word 0\n.text\nmain: nop\n",
          )
        }
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { machine ->
          val program = machine.assemble(listOf(source))
          AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
            assertThrows<CaseFailure> { case.readDataAddress("absent") }
            assertThrows<CaseFailure> { case.readDataAddress("main") }
            assertThrows<CaseFailure> { case.readDataAddress("value", Long.MAX_VALUE) }
            assertThrows<CaseFailure> { case.readWord("absent") }
            assertThrows<CaseFailure> { case.readBytes("absent", 0) }
            assertThrows<CaseFailure> { case.readWord("main") }
            assertThrows<CaseFailure> { case.readBytes("main", 1) }
            assertThrows<CaseFailure> { case.readWord("value", offset = 1) }
            assertThrows<CaseFailure> { case.readBytes("value", 1, offset = Int.MAX_VALUE) }
            assertThrows<IllegalArgumentException> { case.readBytes("value", -1) }
            val lastOffset = (machine.memory.wideDataRange!!.last - 0x100000000L).toInt()
            assertArrayEquals(intArrayOf(0), case.readBytes("wide", 1, offset = lastOffset))
            assertThrows<CaseFailure> { case.readBytes("wide", 2, offset = lastOffset) }
            case.expectCondition(true, "a valid relational contract")
            assertEquals(
                "broken parent link",
                assertThrows<CaseFailure> {
                      case.expectCondition(false, "broken parent link")
                    }
                    .message,
            )
            assertThrows<IllegalArgumentException> { case.expectCondition(true, " ") }
          }
        }
  }
}
