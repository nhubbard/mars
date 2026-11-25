package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PrivateTraceReleaseTest {
  @Test
  fun `private policy storage can be released while public closed histories and detached snapshots remain readable`(
      @TempDir directory: Path,
  ) {
    val path = directory.resolve("store.asm")
    val source = $$".data\nvalue: .word 0\n.text\nli $t0,1\nsw $t0,value\nli $v0,10\nsyscall\n"
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { machine ->
          val program =
              machine.assembleSnapshot(
                  AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
                  null,
                  extended = true,
                  warnings = false,
              )
          AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { execution ->
            val private = execution.traceMemoryAccesses("value", 4)
            val public = execution.traceMemoryAccesses("value", 4)
            execution.runToExit()
            val detached = private.snapshot()
            assertEquals(1, detached.size)
            public.close()
            assertEquals(detached, public.snapshot())
            private.closeAndDiscard()
            private.closeAndDiscard()
            assertThrows<IllegalStateException> { private.snapshot() }
            assertEquals(1, detached.size)
            assertEquals(detached, public.snapshot())
            assertEquals(0, machine.memory.countObservers())
          }
        }
  }
}
