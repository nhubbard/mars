package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class Release6MigrationTest {
  @Test
  fun `new sessions assemble and execute Release 6 and classroom pseudos by default`(
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("default.mars"),
            $$"""
            .data 0x100000000
            .align 0
            input: .byte 0
            .dword 0x0123456789abcdef
            .space 16
            .text
            dla $t0,input
            daddiu $t0,$t0,1
            uld $t1,0($t0)
            usd $t1,9($t0)
            ld $t2,9($t0)
            addi $t3,$zero,-100
            li $t4,-0x080000000
            """
                .trimIndent(),
        )
    MarsComponent.build {
          guiProxy(TestSessions.NO_GUI)
        }
        .context()
        .use { machine ->
          assertEquals(MarsTarget.MARS64, machine.target)
          assertEquals("beqc", InstructionDefaults.get().findByBinaryCode(0x212a0001)?.name)
          val program = machine.assemble(listOf(source))
          assertTrue(program.simulate(100))
          assertEquals(0x0123456789abcdefL, machine.registerFile.getLongValueNoNotify(10))
          assertEquals(-100L, machine.registerFile.getLongValueNoNotify(11))
          assertEquals(Int.MIN_VALUE.toLong(), machine.registerFile.getLongValueNoNotify(12))
        }
  }

  @Test
  fun `the addi pseudo traps on word overflow and diagnoses its scratch source`(
      @TempDir directory: Path,
  ) {
    val source = Files.writeString(directory.resolve("addi.mars"), $$"addi $t1,$t0,1\n")
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(source))
      machine.editIntegerRegister(8, Int.MAX_VALUE)
      machine.editIntegerRegister(9, 42)
      assertThrows<ProcessingException> { program.simulate(5) }
      assertEquals(42L, machine.registerFile.getLongValueNoNotify(9))
      Files.writeString(source, $$"addi $t1,$at,100000\n")
      val failure = assertThrows<ProcessingException> { machine.assemble(listOf(source)) }
      assertTrue(
          "Load the immediate into a different temporary" in
              failure.errors()!!.generateErrorReport(),
      )
    }
  }

  @Test
  fun `legacy two-operand divides explain the quotient and remainder instruction forms`(
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("divides.mars"),
            $$"""
            div $8,$9
            DIVU $8,$9
            ddiv $8,$9
            ddivu $8,$9
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val failure = assertThrows<ProcessingException> { machine.assemble(listOf(source)) }
      val diagnostics = failure.errors()!!.generateErrorReport()
      for ((divide, remainder) in
          listOf("div" to "mod", "divu" to "modu", "ddiv" to "dmod", "ddivu" to "dmodu")) {
        assertTrue("Release 6 removed the two-operand $divide form" in diagnostics)
        assertTrue("or $remainder for the remainder" in diagnostics)
      }
    }
  }

  @Test
  fun `removed branch and floating forms offer specific migration advice`(
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("removed.s"),
            $$"""
            .eqv LIKELY beql
            LIKELY $8,$9,target
            c.eq.s $f0,$f2
            abs.ps $f0,$f2
            lwxc1 $f0,$8($9)
            target: nop
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val failure = assertThrows<ProcessingException> { machine.assemble(listOf(source)) }
      val diagnostics = failure.errors()!!.generateErrorReport()
      assertTrue(
          "Release 6 removed \"beql\". Move the annulled delay-slot instruction" in diagnostics,
          diagnostics,
      )
      assertTrue(
          "Release 6 removed \"c.eq.s\". Use the corresponding cmp operation" in diagnostics,
          diagnostics,
      )
      assertTrue(
          "Release 6 removed \"abs.ps\". Use two scalar single-precision operations" in diagnostics,
          diagnostics,
      )
      assertTrue(
          "Release 6 removed \"lwxc1\". Compute the effective address with daddu" in diagnostics,
          diagnostics,
      )
    }
  }

  @Test
  fun `removed forms explain their replacement after equivalence expansion`(
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("migration.s"),
            $$"""
            .eqv MERGE lwr
            MERGE $8,0($9)
            SWL $8,0($9)
            mflo $8
            bc1f target
            bgezal $8,target
            target: nop
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.assemble(listOf(source))
      machine.selectTarget(MarsTarget.MARS64)
      val failure = assertThrows<ProcessingException> { machine.assemble(listOf(source)) }
      val diagnostics = failure.errors()!!.generateErrorReport()
      for ((instruction, replacement) in
          listOf(
              "lwr" to "Use lw",
              "SWL" to "Use sw",
              "mflo" to "Use explicit destination registers",
              "bc1f" to "Use a cmp instruction",
              "bgezal" to "Use bal",
          )) {
        assertTrue(
            diagnostics.contains("Release 6 removed \"$instruction\". $replacement"),
            diagnostics,
        )
      }
    }
  }
}
