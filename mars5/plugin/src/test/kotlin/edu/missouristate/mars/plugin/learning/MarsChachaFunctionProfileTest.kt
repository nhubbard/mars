package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsChachaFunctionProfileTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `actual ChaCha20 profiles preserve the original grid with changed inputs and real caller completion`() {
    val archive = directory.resolve("profiles.zip")
    javaClass.getResourceAsStream("/learning/chacha-function-profiles.zip")!!.use {
      Files.copy(it, archive)
    }
    val profiles =
        ZipFile(archive.toFile()).use { zip ->
          val entries = zip.entries().asSequence().toList()
          assertEquals(9, entries.size)
          entries.map { entry ->
            assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
            zip.getInputStream(entry).use { MarsFunctionCaseData.parse(it.readBytes()) }
          }
        }
    val cases = profiles.flatten()
    assertEquals(73, cases.size)
    assertEquals(73, cases.map { it.name }.distinct().size)
    assertEquals(95, cases.sumOf { it.calls.size })
    assertTrue(profiles.all { it.sumOf { case -> case.instructionLimit } <= 10_000_000 })
    val callers = cases.filter { it.exit != null }
    assertEquals(11, callers.size)
    for (case in cases) {
      assertEquals(9, case.preservedRegisters.size)
      assertEquals(8, case.stackAlignment)
      assertEquals(0, case.callerStackBytes)
      assertTrue(case.instructionLimit in setOf(100_000L, 1_000_000L))
      val observed = if (case.exit == null) case.calls else case.calls.drop(1)
      if (case.exit != null) {
        assertEquals("main", case.calls.first().entry)
        assertEquals(case.calls.first().returnTo, case.calls.last().entry)
        assertEquals("main_done", case.calls.last().returnTo)
        assertEquals(8, case.calls.first().setupMemory.size)
        assertTrue(case.calls.last().setupMemory.isEmpty())
        assertEquals("status", case.exit!!.memory.single().label)
      }
      for (call in observed) {
        assertEquals(8, call.memory.size)
        assertEquals(listOf(4160, 4160, 128, 96, 4, 4, 8, 4), call.memory.map { it.byteLength })
        assertEquals(8, call.accessPolicies.size)
        assertTrue(call.accessPolicies.all { it.maxAccesses == 250_000 })
        assertEquals(250_000, call.accessHistory!!.maxAccesses)
        assertEquals(listOf("source", "status"), call.accessHistory!!.regions.map { it.at.label })
        if (case.exit == null) assertEquals(8, call.setupMemory.size)
      }
    }
    assertEquals(
        listOf(3, 3, 2, 4, 4),
        cases.filter { it.name.startsWith("repeated ") }.map { it.calls.size },
    )
    val rejected =
        cases.single { it.name == "invalid count 4294967296 before pointers" }.calls.single()
    assertEquals(0x100000000L, rejected.arguments.getValue("a3"))
    assertTrue(rejected.accessPolicies.all { it.readable.isEmpty() && it.writable.isEmpty() })
    assertEquals(1L, rejected.expectedRegisters.getValue("v0"))
  }
}
