package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.mips.hardware.MemoryState.Companion.BIG_ENDIAN
import edu.missouristate.mars.mips.hardware.MemoryState.Companion.LITTLE_ENDIAN
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class AddressMemorySnapshotTest {
  @Test
  fun `address snapshots retain wide and unaligned bytes in either order without simulated reads`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("snapshot.asm").also {
          it.writeText(
              $$"""
                  .data 0x100000000
              values: .word 0, 0
                  .text
                  dla $t0, values
                  li $t1, 0x80ff7f01
                  sw $t1, 0($t0)
                  li $t1, 0x12345678
                  sw $t1, 4($t0)
                  li $sp, 0x7fff0000
                  sd $t0, -8($sp)
              ready:
                  li $v0, 10
                  syscall
              """
                  .trimIndent(),
          )
        }
    for (order in listOf(LITTLE_ENDIAN, BIG_ENDIAN)) {
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()
          .use { machine ->
            val program = machine.assemble(listOf(source))
            machine.memory.byteOrder = order
            AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
              val data = case.traceMemoryAccesses(0x100000000L, 8)
              val stack = case.traceMemoryAccesses(0x7ffefff8L, 8)
              val execution = case.traceExecution()
              case.runUntil("ready")
              val dataBefore = data.snapshot()
              val stackBefore = stack.snapshot()
              val instructions = execution.snapshot().instructions
              val undo = machine.backStepper.canBackstep()
              val expected =
                  if (order) {
                    intArrayOf(1, 127, 255, 128, 120, 86, 52, 18)
                  } else intArrayOf(128, 255, 127, 1, 18, 52, 86, 120)
              assertArrayEquals(expected.copyOfRange(1, 7), case.readBytes(0x100000001L, 6))
              assertArrayEquals(case.readBytes("values", 8), case.readBytes(0x100000000L, 8))
              val pointer =
                  if (order) {
                    intArrayOf(0, 0, 0, 0, 1, 0, 0, 0)
                  } else intArrayOf(0, 0, 0, 1, 0, 0, 0, 0)
              assertArrayEquals(pointer, case.readBytes(0x7ffefff8L, 8))
              val detached = case.readBytes(0x100000000L, 8)
              detached.fill(0)
              assertArrayEquals(expected, case.readBytes(0x100000000L, 8))
              assertEquals(dataBefore, data.snapshot())
              assertEquals(stackBefore, stack.snapshot())
              assertEquals(instructions, execution.snapshot().instructions)
              assertEquals(undo, machine.backStepper.canBackstep())
              machine.simulator().backstepAsync().join()
              assertEquals(List(8) { 0 }, case.readBytes(0x7ffefff8L, 8).toList())
            }
          }
    }
  }

  @Test
  fun `invalid and overflowing intervals fail explicitly while empty intervals inspect nothing`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("empty.asm").also {
          it.writeText(
              $$"""
              .text
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
            assertThrows<IllegalArgumentException> { case.readBytes(-1L, 0) }
            assertThrows<IllegalArgumentException> { case.readBytes(0L, -1) }
            assertThrows<IllegalArgumentException> { case.readBytes(Long.MAX_VALUE, 2) }
            assertThrows<CaseFailure> { case.readBytes(0L, 1) }
            assertThrows<CaseFailure> { case.readBytes(0x200000000L, 1) }
            val wideEnd = requireNotNull(machine.memory.wideDataRange).last
            assertThrows<CaseFailure> { case.readBytes(wideEnd, 2) }
            assertArrayEquals(intArrayOf(), case.readBytes(Long.MAX_VALUE, 0))
          }
        }
  }
}
