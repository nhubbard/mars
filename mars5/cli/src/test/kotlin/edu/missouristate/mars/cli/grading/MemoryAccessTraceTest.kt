package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.cli.grading.MemoryAccessTrace.Access
import edu.missouristate.mars.di.MarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class MemoryAccessTraceTest {
  @Test
  fun `native widths and crossing overlaps are retained without recording external inspection`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("access.asm").also {
          it.writeText(
              $$"""
                  .data
              values: .word 0x80ff7f00, 0x11223344, 0
                  .text
                  dla $t0, values
                  lw $t1, 0($t0)
                  lbu $t2, 5($t0)
                  sb $t2, 7($t0)
              ready:
                  lw $t3, 4($t0)
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
            val base = case.readDataAddress("values")
            val all = case.traceMemoryAccesses("values", 12)
            val leftCrossing = case.traceMemoryAccesses("values", offset = 3)
            val rightCrossing = case.traceMemoryAccesses(base + 5)
            case.runUntil("ready")
            val prefix = all.snapshot()
            assertEquals(
                listOf(
                    Access(base, 4, false),
                    Access(base + 5, 1, false),
                    Access(base + 7, 1, true),
                ),
                prefix,
            )
            assertEquals(listOf(Access(base, 4, false)), leftCrossing.snapshot())
            leftCrossing.expectReadsWithin(base, 4)
            assertThrows<CaseFailure> { leftCrossing.expectReadsWithin(base + 3, 1) }
            rightCrossing.expectReadsWithin(base + 5, 1)
            all.expectReadsWithin(base, 6)
            assertThrows<CaseFailure> { all.expectReadsWithin(base, 5) }
            assertThrows<UnsupportedOperationException> { (prefix as MutableList).clear() }
            case.readWord("values")
            case.readBytes("values", 8)
            case.setWord("values", 99)
            assertEquals(prefix, all.snapshot())
            all.close()
            all.close()
            case.runToExit()
            assertEquals(prefix, all.snapshot())
            assertEquals(
                listOf(Access(base + 5, 1, false), Access(base + 4, 4, false)),
                rightCrossing.snapshot(),
            )
            assertThrows<CaseFailure> { rightCrossing.expectReadsWithin(base + 5, 1) }
          }
        }
  }

  @Test
  fun `zero permitted bytes exclude reads not writes and overflow is explicit`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("empty.asm").also {
          it.writeText(
              $$"""
                  .data
              value: .word 0
                  .text
                  la $t0, value
                  sw $zero, 0($t0)
              stores_ready:
                  nop
                  lw $t1, 0($t0)
              ready:
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
            val base = case.readDataAddress("value")
            val complete = case.traceMemoryAccesses("value", 4)
            val small = case.traceMemoryAccesses("value", 4, maxAccesses = 1)
            assertThrows<IllegalArgumentException> { case.traceMemoryAccesses(base, 0) }
            assertThrows<IllegalArgumentException> { case.traceMemoryAccesses(-1, 1) }
            assertThrows<IllegalArgumentException> { case.traceMemoryAccesses(Long.MAX_VALUE, 2) }
            assertThrows<IllegalArgumentException> {
              case.traceMemoryAccesses(base, maxAccesses = 0)
            }
            assertThrows<CaseFailure> { case.traceMemoryAccesses("absent") }
            assertThrows<CaseFailure> { case.traceMemoryAccesses("ready") }
            val high = case.traceMemoryAccesses(Long.MAX_VALUE)
            high.expectReadsWithin(Long.MAX_VALUE, 0)
            assertThrows<IllegalArgumentException> { complete.expectReadsWithin(-1, 0) }
            assertThrows<IllegalArgumentException> { complete.expectReadsWithin(base, -1) }
            assertThrows<IllegalArgumentException> { complete.expectReadsWithin(Long.MAX_VALUE, 2) }
            case.runUntil("stores_ready")
            complete.expectReadsWithin(base, 0)
            small.expectReadsWithin(base, 0)
            assertEquals(listOf(Access(base, 4, true)), small.snapshot())
            case.runUntil("ready")
            assertThrows<CaseFailure> { complete.expectReadsWithin(base, 0) }
            complete.expectReadsWithin(base, 4)
            assertThrows<CaseFailure> { small.snapshot() }
            assertThrows<CaseFailure> { small.expectReadsWithin(base, 4) }
            high.expectReadsWithin(Long.MAX_VALUE, 0)
            case.runToExit()
          }
        }
  }

  @Test
  fun `wide addresses and forward replay retain full history`(@TempDir directory: Path) {
    val source =
        directory.resolve("wide-access.asm").also {
          it.writeText(
              $$"""
                  .data 0x100000000
              value: .word 17
                  .text
                  dla $t0, value
                  lw $t1, 0($t0)
                  addiu $t1, $t1, 6
                  sw $t1, 0($t0)
              ready:
                  nop
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
            val trace = case.traceMemoryAccesses("value", 4)
            case.runUntil("ready")
            val first = listOf(Access(0x100000000L, 4, false), Access(0x100000000L, 4, true))
            assertEquals(first, trace.snapshot())
            assertEquals(23, case.readWord("value"))
            trace.expectReadsWithin(0x100000000L, 4)
            assertThrows<CaseFailure> { trace.expectReadsWithin(0, 4) }
            machine.simulator().backstepAsync().join()
            assertEquals(first, trace.snapshot())
            assertEquals(17, case.readWord("value"))
            case.step()
            assertEquals(first + Access(0x100000000L, 4, true), trace.snapshot())
            assertEquals(23, case.readWord("value"))
          }
        }
  }

  @Test
  fun `case failure detaches observations and a fresh case has no history`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("cleanup-access.asm").also {
          it.writeText(
              $$"""
                  .data
              value: .word 0
                  .text
                  la $t0, value
                  lw $t1, 0($t0)
              ready:
                  nop
                  lw $t2, 0($t0)
                  nop
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
          val case = AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream())
          val trace = case.traceMemoryAccesses("value", 4)
          assertThrows<CaseFailure> {
            case.use {
              it.runUntil("ready")
              it.expectCondition(false, "forced case failure")
            }
          }
          val prefix = trace.snapshot()
          assertEquals(1, prefix.size)
          machine.simulator().runAsync(3, null).join()
          assertEquals(prefix, trace.snapshot())
          MarsComponent.build {
                guiProxy(HeadlessGuiProxy())
              }
              .context()
              .use { fresh ->
                val next = fresh.assemble(listOf(source))
                AssemblyCase(fresh, next.symbolMap(), ByteArrayOutputStream()).use { independent ->
                  val nextTrace = independent.traceMemoryAccesses("value", 4)
                  assertEquals(emptyList<Access>(), nextTrace.snapshot())
                  independent.runUntil("ready")
                  assertEquals(1, nextTrace.snapshot().size)
                  assertEquals(prefix, trace.snapshot())
                }
              }
        }
  }
}
