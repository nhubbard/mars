package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.MemoryState
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvFileSource

class GnuMips64EncodingTest {
  @Test
  fun `trap code must fit the encoded field`(@TempDir directory: Path) {
    val file = Files.writeString(directory.resolve("invalid.s"), $$"teq $12,$9,1024\n")
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val failure = assertThrows<ProcessingException> { machine.assemble(listOf(file)) }
      assertTrue(
          "Trap code must be an unsigned 10-bit integer" in
              failure.errors()!!.generateErrorReport(),
      )
      Files.writeString(file, $$"tne $12,$9,-1\n")
      assertThrows<ProcessingException> { machine.assemble(listOf(file)) }
    }
  }

  @Test
  fun `a coded trap raises the same exception as a two-register trap`(@TempDir directory: Path) {
    val file = Files.writeString(directory.resolve("trap.s"), $$"teq $zero,$zero,7\n")
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(file))
      val failure = assertThrows<ProcessingException> { program.simulate(1) }
      assertTrue(failure.message.orEmpty().endsWith(": trap"))
    }
  }

  @ParameterizedTest(name = "{0}, {1} endian")
  @CsvFileSource(resources = ["/gnu-mips64/probe.tsv"], delimiter = '\t')
  fun `encodings and operands match GNU binutils`(
      source: String,
      byteOrder: String,
      word: String,
      mnemonic: String,
      operands: String,
      @TempDir directory: Path,
  ) {
    val file = Files.writeString(directory.resolve("probe.s"), source + "\n")
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      machine.memory.byteOrder =
          if (byteOrder == "little") MemoryState.LITTLE_ENDIAN else MemoryState.BIG_ENDIAN
      val statement =
          machine
              .assemble(listOf(file), null, extended = false, warnings = false)
              .machineList!!
              .single()
      val expectedWord = word.toUInt(16).toInt()
      assertEquals(expectedWord, statement.binaryStatement)
      val decoded = ProgramStatement(expectedWord, statement.addressBits, machine.instructionSet)
      assertEquals(mnemonic, decoded.instruction?.name)
      assertEquals(operands.split(',').map(String::toInt), decoded.operands.take(3))
    }
  }
}
