package edu.missouristate.mars.plugin.debug

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.run.MarsExecutionSources
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DebugExecutionTest {
  @Test
  fun `debug binding rejects an unloaded session and retains its captured program`() {
    val path = Path.of("debug-execution.s").toAbsolutePath()
    val snapshot = AssemblySourceSnapshot(listOf(path), mapOf(path to "nop\n"))
    val sources =
        MarsExecutionSources(
            CapturedAssemblySources(snapshot, null, emptyMap(), mapOf(path to path)),
        )
    MarsSessionService.createMarsComponent().context().use { machine ->
      val failure = assertThrows<IllegalStateException> { DebugExecution(machine, sources) }
      assertEquals("Load an assembled program before starting Debug", failure.message)
      val program = machine.assembleSnapshot(snapshot, null, true, false)
      val execution = DebugExecution(machine, sources)
      assertSame(machine, execution.machine)
      assertSame(program, execution.program)
      assertSame(sources, execution.symbols.sources)
      val replacement = machine.assembleSnapshot(snapshot, null, true, false)
      assertNotSame(replacement, execution.program)
      assertSame(program, execution.program)
    }
  }
}
