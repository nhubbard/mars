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

class ExecutionPointTraceTest {
  @Test
  fun `point trace counts repeated label visits excluding adjacent instructions and closes with case`(
      @TempDir directory: Path,
  ) {
    val entry =
        directory.resolve("publications.asm").also {
          it.writeText(
              $$"""
                  .data
              value: .word 0
                  .text 0x100400000
                  li $t0, 3
              publication:
                  addiu $t0, $t0, -1
                  bnezc $t0, publication
                  nop
                  nop
                  nop
              done:
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
          val case = AssemblyCase(session, program.symbolMap(), ByteArrayOutputStream())
          lateinit var point: ExecutionTrace
          case.use {
            assertThrows<CaseFailure> { it.traceExecution("value") }
            assertThrows<CaseFailure> { it.traceExecution("missing") }
            point = it.traceExecution("publication")
            it.startAt("publication")
            it.setRegister("t0", 4)
            it.step(6)
            point.expectCounts(3, 0, 0)
          }
          session.simulator().runAsync(1, null).join()
          assertEquals(3L, point.snapshot().instructions)
        }
  }
}
