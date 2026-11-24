package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvFileSource

class QemuMips64ExecutionTest {
  @ParameterizedTest(name = "{0}, {1} endian")
  @CsvFileSource(resources = ["/qemu-mips64/expected.csv"])
  fun `portable instruction sequences produce the same bytes as QEMU I6400`(
      probe: String,
      byteOrder: String,
      expectedHex: String,
      @TempDir directory: Path,
  ) {
    val programText = buildString {
      appendLine(".text")
      appendLine(fixture("$probe.asm"))
      if (probe.startsWith("faults/")) {
        appendLine(".ktext 0x80000180")
        appendLine(fixture("exception-handler.s"))
        appendLine("done: nop")
      }
    }
    val source =
        Files.writeString(directory.resolve(probe.substringAfterLast('/') + ".asm"), programText)
    val inputBytes =
        fixture("input.hex")
            .lineSequence()
            .filter(String::isNotBlank)
            .joinToString("")
            .hexToByteArray()
    val expected = expectedHex.hexToByteArray()
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val memory = machine.memory
      memory.byteOrder = byteOrder == "little"
      val program =
          try {
            machine.assemble(
                listOf(source),
                handler = null,
                extended = probe == "scalar-branch-aliases",
                warnings = false,
            )
          } catch (failure: ProcessingException) {
            throw AssertionError(failure.errors()?.generateErrorAndWarningReport(), failure)
          }
      val input = memory.dataBaseAddress
      val output = input + 0x1000
      for ((offset, byte) in inputBytes.withIndex()) memory.setByte(input + offset, byte.toInt())
      for (offset in expected.indices) memory.setByte(output + offset, 0xa5)
      machine.editIntegerRegister(4, input)
      machine.editIntegerRegister(5, output)

      if (probe.startsWith("faults/")) {
        val handlerStop = program.machineList!!.last().address
        machine.editIntegerRegister(6, machine.registerFile.programCounterBits)
        assertFalse(program.simulateFromPC(intArrayOf(handlerStop), 512))
        assertEquals(
            handlerStop,
            machine.registerFile.programCounter,
            "The exception handler must finish",
        )
      } else {
        assertTrue(
            program.simulate(512),
            "The probe must reach the end of its instruction sequence",
        )
      }
      val actual = ByteArray(expected.size) { memory.getByte(output + it).toByte() }
      assertArrayEquals(expected, actual, "$probe, $byteOrder endian")
    }
  }

  private fun fixture(name: String): String =
      checkNotNull(javaClass.getResourceAsStream("/qemu-mips64/$name")).bufferedReader().use {
        it.readText()
      }
}
