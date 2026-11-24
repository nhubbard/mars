package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsWavReverseFunctionProfileTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `actual WAV profiles retain maximum alias reversal reuse and the complete saved file`() {
    val path = directory.resolve("profiles.zip")
    javaClass.getResourceAsStream("/learning/wav-reverse-profiles-v20.zip")!!.use {
      Files.copy(it, path)
    }
    val profiles =
        ZipFile(path.toFile()).use { zip ->
          val entries = zip.entries().asSequence().toList()
          assertEquals(7, entries.size)
          entries.map { entry ->
            assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
            zip.getInputStream(entry).use { MarsFunctionCaseData.parse(it.readBytes()) }
          }
        }
    val cases = profiles.flatten()
    assertEquals(35, cases.size)
    assertEquals(35, cases.map { it.name }.distinct().size)
    assertEquals(40, cases.sumOf { it.calls.size })
    assertTrue(profiles.all { it.sumOf { c -> c.instructionLimit } <= 40_000_000 })
    val max = cases.single { it.name == "maximum stereo in-place reversal" }.calls.single()
    assertEquals(65_536L, max.expectedRegisters.getValue("v1"))
    assertEquals(max.argumentAddresses["a0"], max.argumentAddresses["a2"])
    assertEquals(262_144, max.memory.single().byteLength)
    assertTrue(max.memory.single().repeat > 1)
    assertTrue(max.setupMemory.any { it.byteLength == 262_144 && it.repeat > 1 })
    val reuse = cases.single { it.name == "independent repeated reversals" }
    assertEquals(6, reuse.calls.size)
    assertEquals(10_000_000L, reuse.instructionLimit)
    for (c in cases.filter { it.name != "reverse the complete filtered WAV pipeline" }) {
      assertEquals(9, c.preservedRegisters.size)
      assertTrue(
          c.calls.all {
            it.callerAccess!!.objects.size == 16 && it.callerAccess!!.maxAccesses == 1_000_000
          },
      )
    }
    val pipeline = cases.last()
    assertEquals("main", pipeline.calls.single().entry)
    assertEquals("results_ready", pipeline.calls.single().returnTo)
    val files = pipeline.fileSystem!!
    assertEquals(7, files.maxReadSize)
    assertEquals(5, files.maxWriteSize)
    assertEquals(19, files.firstDescriptor)
    assertEquals(setOf("output.wav"), files.writablePaths)
    assertEquals(60, pipeline.calls.single().expectedFiles!!.getValue("output.wav")!!.length / 2)
    assertEquals(listOf("status", "count", "clips"), pipeline.exit!!.memory.map { it.label })
  }
}
