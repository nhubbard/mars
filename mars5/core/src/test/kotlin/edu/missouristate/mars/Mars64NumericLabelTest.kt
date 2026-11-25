package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class Mars64NumericLabelTest {
  @Test
  fun `forward and backward references find the nearest repeated label in assembly order`(
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("locals.mars"),
            $$"""
            .data
            .float 1f
            .text
            addiu $t0,$zero,2
            beq $zero,$zero,7f
            nop
            addiu $t0,$zero,100
            7: addiu $t0,$t0,-1
            bnez $t0,7b
            nop
            beq $zero,$zero,7f
            nop
            7: addiu $t0,$t0,3
            nop
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(source))
      assertEquals(
          listOf(0x10000002, 0x1500fffe, 0x10000001),
          program.machineList!!
              .filter { it.instruction?.name in setOf("beq", "bne") }
              .map(ProgramStatement::binaryStatement),
      )
      assertEquals(1f.toRawBits(), machine.memory.getWordNoNotify(machine.memory.dataBaseAddress))
      assertTrue(program.simulate(30))
      assertEquals(3L, machine.registerFile.getLongValueNoNotify(8))
    }
  }

  @Test
  fun `numeric labels bind within each assembly source with explicit direction`(
      @TempDir directory: Path,
  ) {
    val later = Files.writeString(directory.resolve("later.mars"), "11: nop\n")
    val source = directory.resolve("first.mars")
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      for ((text, reference) in listOf("b 11f" to "11f", "b 7b\n7: nop" to "7b")) {
        Files.writeString(source, text)
        val failure =
            assertThrows<ProcessingException> {
              machine.assemble(listOf(source, later))
            }
        val report = failure.errors()!!.generateErrorReport()
        assertTrue(
            report.contains("Numeric label reference \"$reference\" has no matching definition"),
            report,
        )
      }
    }
  }

  @Test
  fun `numeric labels can identify auto-aligned data through relocations`(
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("numeric-data.mars"),
            $$"""
            .data
            .byte 255
            12: .word 42
            .text
            lui $t0,%hi(12b)
            lw $t1,%lo(12b)($t0)
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      assertTrue(machine.assemble(listOf(source)).simulate(10))
      assertEquals(42L, machine.registerFile.getLongValueNoNotify(9))
    }
  }
}
