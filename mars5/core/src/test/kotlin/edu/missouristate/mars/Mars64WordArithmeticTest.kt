package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class Mars64WordArithmeticTest {
  @Test
  fun `editor recognizes legacy arithmetic while wide operand lookup uses native forms`() {
    val editor = InstructionDefaults.editorCatalog()
    val wide = InstructionDefaults.forTarget(MarsTarget.MARS64)
    assertTrue(editor.matchOperator("mflo")!!.isNotEmpty())
    assertTrue(editor.matchOperator("div")!!.any { it.exampleFormat == $$"div $t1,$t2" })
    assertNull(wide.matchOperator("mflo"))
    assertTrue(wide.matchOperator("div")!!.none { it.exampleFormat == $$"div $t1,$t2" })
    assertNull(editor.findByBinaryCode(0x014b001a))
  }

  @ParameterizedTest
  @CsvSource(
      "mul,014b4898,-7,3,-21",
      "mulu,014b4899,-1,2,-2",
      "div,014b489a,-7,3,-2",
      "div,014b489a,-2147483648,-1,-2147483648",
      "divu,014b489b,-1,1,-1",
      "divu,014b489b,-1,2,2147483647",
      "muh,014b48d8,-7,3,-1",
      "muhu,014b48d9,-1,-1,-2",
      "mod,014b48da,-7,3,-1",
      "modu,014b48db,-1,2,1",
  )
  fun `Release 6 word arithmetic writes one sign extended result and reverses it`(
      mnemonic: String,
      encoding: String,
      left: Long,
      right: Long,
      expected: Long,
      @TempDir directory: Path,
  ) {
    val file = Files.writeString(directory.resolve("word.s"), "$mnemonic $9,$10,$11\n")
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(file), null, extended = false, warnings = false)
      val statement = program.machineList!!.single()
      val word = encoding.toInt(16)
      assertEquals(word, statement.binaryStatement)
      assertEquals(mnemonic, session.instructionSet.findByBinaryCode(word)?.name)
      session.editIntegerRegister(9, 0x1_0000_0000)
      session.editIntegerRegister(10, left)
      session.editIntegerRegister(11, right)
      val beforeHi = session.registerFile.getValueNoNotify(33)
      val beforeLo = session.registerFile.getValueNoNotify(34)
      program.simulate(1)
      assertEquals(expected, session.registerFile.getLongValueNoNotify(9))
      assertEquals(beforeHi, session.registerFile.getValueNoNotify(33))
      assertEquals(beforeLo, session.registerFile.getValueNoNotify(34))
      session.backStepper.backStep()
      assertEquals(0x1_0000_0000L, session.registerFile.getLongValueNoNotify(9))
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["div", "divu", "mod", "modu"])
  fun `division by zero traps without writing the destination`(
      mnemonic: String,
      @TempDir directory: Path,
  ) {
    val file = Files.writeString(directory.resolve("zero.s"), $$"$$mnemonic $9,$10,$zero\n")
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(file), null, extended = false, warnings = false)
      session.editIntegerRegister(9, Long.MIN_VALUE)
      session.editIntegerRegister(10, -7)
      assertThrows(ProcessingException::class.java) { program.simulate(1) }
      assertEquals(Long.MIN_VALUE, session.registerFile.getLongValueNoNotify(9))
    }
  }

  @Test
  fun `only MARS32 decodes legacy word multiply and divide`(@TempDir directory: Path) {
    val file = Files.writeString(directory.resolve("legacy.s"), $$"div $10,$11\n")
    val words =
        mapOf(0x014b001a to "div", 0x014b0018 to "mult", 0x00004812 to "mflo", 0x714b4802 to "mul")
    TestSessions.create().use { session ->
      for ((word, mnemonic) in words) assertEquals(
          mnemonic,
          session.instructionSet.findByBinaryCode(word)?.name,
      )
      session.assemble(listOf(file), null, extended = false, warnings = false)
      session.selectTarget(MarsTarget.MARS64)
      for (word in words.keys) assertNull(session.instructionSet.findByBinaryCode(word))
      assertThrows(ProcessingException::class.java) {
        session.assemble(listOf(file), null, extended = false, warnings = false)
      }
    }
  }

  @Test
  fun `word arithmetic templates emit Release 6 register result instructions`(
      @TempDir directory: Path,
  ) {
    val file =
        Files.writeString(
            directory.resolve("pseudo.s"),
            $$"""
            mulu $9,$10,-100
            div $11,$10,100000
            rem $12,$10,$13
            remu $14,$10,-100
            """
                .trimIndent(),
        )
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(file))
      assertEquals(
          listOf("addiu", "mulu", "lui", "ori", "div", "mod", "addiu", "modu"),
          program.machineList!!.map { it.instruction?.name },
      )
      session.editIntegerRegister(10, 200003)
      session.editIntegerRegister(13, 10)
      program.simulate(8)
      assertEquals(
          listOf(-20_000_300, 2, 3, 200003),
          listOf(9, 11, 12, 14).map(session.registerFile::getValueNoNotify),
      )
    }
  }
}
