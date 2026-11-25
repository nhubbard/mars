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
import org.junit.jupiter.params.provider.ValueSource

class PcRelativeInstructionTest {
  @Test
  fun `shared PC relative forms use Release 6 words and sign extend word loads in MARS64`(
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("shared-pc.s"),
            $$"""
            addiupc $8,-4
            lwpc $9,12
            auipc $10,1
            aluipc $11,-1
            sd $zero,0($zero)
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
      val instructions = program.machineList!!
      val words = listOf(0xed07_ffffu, 0xed28_0003u, 0xed5e_0001u, 0xed7f_ffffu, 0xfc00_0000u)
      assertEquals(words.map { it.toInt() }, instructions.map(ProgramStatement::binaryStatement))
      for (statement in instructions.take(4)) {
        val decoded =
            ProgramStatement(statement.binaryStatement, statement.address, machine.instructionSet)
        assertEquals(statement.instruction?.name, decoded.instruction?.name)
      }
      program.simulate(4)
      val start = instructions.first().address.toLong()
      assertEquals(start - 4, machine.registerFile.getLongValueNoNotify(8))
      assertEquals(words.last().toInt().toLong(), machine.registerFile.getLongValueNoNotify(9))
      assertEquals(start + 8 + 0x10000, machine.registerFile.getLongValueNoNotify(10))
      assertEquals(
          (start + 12 - 0x10000) and -0x10000L,
          machine.registerFile.getLongValueNoNotify(11),
      )
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [MemoryState.LITTLE_ENDIAN, MemoryState.BIG_ENDIAN])
  fun `wide PC relative loads align the base and preserve unsigned bits`(
      littleEndian: Boolean,
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("wide-pc.s"),
            $$"""
            .text
            lwupc $9,12
            ldpc $10,8
            lui $12,0xabcd
            sd $zero,0($zero)
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      assertThrows<ProcessingException> {
        machine.assemble(listOf(source), null, extended = false, warnings = false)
      }
      machine.selectTarget(MarsTarget.MARS64)
      machine.memory.byteOrder = littleEndian
      val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
      val instructions = program.machineList!!
      assertEquals(0xed30_0003u.toInt(), instructions[0].binaryStatement)
      assertEquals(0xed58_0001u.toInt(), instructions[1].binaryStatement)
      for (statement in instructions.take(2)) {
        val decoded =
            ProgramStatement(statement.binaryStatement, statement.address, machine.instructionSet)
        assertEquals(statement.operands.toList(), decoded.operands.toList())
      }
      program.simulate(2)
      assertEquals(0xfc00_0000L, machine.registerFile.getLongValueNoNotify(9))
      val first = instructions[2].binaryStatement.toUInt().toLong()
      val second = 0xfc00_0000L
      val expected = if (littleEndian) (second shl 32) or first else (first shl 32) or second
      assertEquals(expected, machine.registerFile.getLongValueNoNotify(10))
      machine.backStepper.backStep()
      assertEquals(0L, machine.registerFile.getLongValueNoNotify(10))
    }
  }

  @Test
  fun `PC relative instructions use this instruction address and byte displacements`(
      @TempDir directory: Path,
  ) {
    val source = directory.resolve("pc.s")
    TestSessions.create().use { machine ->
      Files.writeString(
          source,
          $$"""
          .text
          addiupc $t0,-4
          lwpc $t1,8
          auipc $t2,1
          aluipc $t3,1
          """
              .trimIndent(),
      )
      val program = machine.assemble(listOf(source))
      val instructions = program.machineList!!
      val address = instructions.first().address
      val decoded =
          ProgramStatement(instructions.first().binaryStatement, address, machine.instructionSet)
      assertEquals(-4, decoded.getOperands()!![1])
      assertTrue(program.simulate(10))
      assertEquals(address - 4, machine.registerFile.getValue(8))
      assertEquals(instructions.last().binaryStatement, machine.registerFile.getValue(9))
      assertEquals(address + 8 + 0x10000, machine.registerFile.getValue(10))
      assertEquals((address + 12 + 0x10000) and -0x10000, machine.registerFile.getValue(11))
      machine.backStepper.backStep()
      assertEquals(0, machine.registerFile.getValue(11))

      Files.writeString(source, $$"addiupc $t0, 5")
      val problem = assertThrows<ProcessingException> { machine.assemble(listOf(source)) }
      assertTrue(problem.errors()!!.generateErrorReport().contains("multiple of four"))
    }
  }
}
