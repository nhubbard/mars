package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ExecutionTraceTest {
  @Test
  fun `range counters exclude callees but include caller delay slots and both branch decisions`(
      @TempDir directory: Path,
  ) {
    val entry =
        directory.resolve("calls.asm").also {
          it.writeText(
              $$"""
                  .data
                  value: .word 0
                  .text
                  li $t0, 2
              window:
                  addiu $t0, $t0, -1
                  bne $t0, $zero, window
                  nop
                  jal helper
                  nop
              finish:
                  li $v0, 10
                  syscall
              helper:
                  beq $zero, $zero, helper_done
                  nop
              helper_done:
                  jr $ra
                  nop
              """
                  .trimIndent(),
          )
        }
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { session ->
          val program = session.assemble(listOf(entry))
          AssemblyCase(session, program.symbolMap(), ByteArrayOutputStream()).use { case ->
            assertThrows<IllegalArgumentException> {
              case.traceExecution("finish", "window")
            }
            assertThrows<IllegalArgumentException> {
              case.traceExecution("window", "window")
            }
            assertThrows<CaseFailure> { case.traceExecution("value", "finish") }
            assertThrows<CaseFailure> { case.traceExecution("missing", "finish") }
            val all = case.traceExecution()
            val range = case.traceExecution("window", "finish")
            val activeRange = case.traceExecution("window", "finish")
            case.runUntil("finish")
            all.expectCounts(13, 2, 1)
            range.expectCounts(8, 1, 1)
            assertEquals(3L, all.snapshot().branches)
            val prefix = all.snapshot()
            range.close()
            range.close()
            val suffix = case.traceExecution()
            case.runToExit()
            all.expectCounts(15, 2, 1)
            range.expectCounts(8, 1, 1)
            suffix.expectCounts(2, 0, 0)
            activeRange.expectCounts(8, 1, 1)
            assertEquals(13L, prefix.instructions)
            assertThrows<CaseFailure> { all.expectCounts(14, 2, 1) }
            assertThrows<IllegalArgumentException> { all.expectCounts(-1, 0, 0) }
            assertThrows<IllegalArgumentException> { all.expectCounts(0, -1, 0) }
            assertThrows<IllegalArgumentException> { all.expectCounts(0, 0, -1) }
          }
        }
  }

  @Test
  fun `backstep leaves forward counts intact and replay contributes another event`(
      @TempDir directory: Path,
  ) {
    val entry = directory.resolve("replay.asm").also { it.writeText("nop\nnop\nnop\n") }
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { session ->
          val program = session.assemble(listOf(entry))
          AssemblyCase(session, program.symbolMap(), ByteArrayOutputStream()).use { case ->
            val trace = case.traceExecution()
            case.step(2)
            trace.expectCounts(2, 0, 0)
            session.simulator().backstepAsync().join()
            trace.expectCounts(2, 0, 0)
            case.step()
            trace.expectCounts(3, 0, 0)
          }
        }
  }

  @Test
  fun `compact branch counters use full instruction addresses above four GiB`(
      @TempDir directory: Path,
  ) {
    val entry =
        directory.resolve("wide.asm").also {
          it.writeText(
              $$"""
                  .text 0x100400000
                  .globl main
              main:
                  bc finish
                  nop
              finish:
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
        .use { session ->
          val program = session.assemble(listOf(entry))
          AssemblyCase(session, program.symbolMap(), ByteArrayOutputStream()).use { case ->
            case.startAt("main")
            val all = case.traceExecution()
            val range = case.traceExecution("main", "finish")
            case.runToExit()
            all.expectCounts(3, 1, 0)
            range.expectCounts(1, 1, 0)
          }
        }
  }

  @Test
  fun `case cleanup releases a counter after a failed check and fresh sessions remain independent`(
      @TempDir directory: Path,
  ) {
    val entry = directory.resolve("nops.asm").also { it.writeText("nop\nnop\nnop\n") }
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { session ->
          val program = session.assemble(listOf(entry))
          val case = AssemblyCase(session, program.symbolMap(), ByteArrayOutputStream())
          val counter = case.traceExecution()
          assertThrows<CaseFailure> {
            case.use {
              it.step()
              counter.expectCounts(2, 0, 0)
            }
          }
          session.simulator().runAsync(1, null).join()
          counter.expectCounts(1, 0, 0)
          MarsComponent.build {
                guiProxy(HeadlessGuiProxy())
              }
              .context()
              .use { other ->
                val fresh = other.assemble(listOf(entry))
                AssemblyCase(other, fresh.symbolMap(), ByteArrayOutputStream()).use { freshCase ->
                  val independent = freshCase.traceExecution()
                  independent.expectCounts(0, 0, 0)
                  freshCase.step()
                  independent.expectCounts(1, 0, 0)
                  counter.expectCounts(1, 0, 0)
                }
              }
        }
  }
}
