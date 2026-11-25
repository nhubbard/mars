package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

class IntegerOperandWidthTest {
  @ParameterizedTest
  @EnumSource(MarsTarget::class)
  fun `logic and moves preserve the selected register width and reverse`(
      target: MarsTarget,
      @TempDir directory: Path,
  ) {
    val wide = target == MarsTarget.MARS64
    val file =
        Files.writeString(
            directory.resolve("logic.s"),
            $$"""
            and $t0,$a0,$a1
            or $t1,$a0,$a1
            xor $t2,$a0,$a1
            not $t3,$a0
            andi $t4,$a0,0xffff
            ori $t5,$a0,0xffff
            xori $t6,$a0,0xffff
            move $t7,$a0
            $${if (wide) "selnez" else "movn"} $s0,$a0,$a1
            $${if (wide) "seleqz" else "movz"} $s1,$a0,$zero
            seleqz $s2,$a0,$a1
            selnez $s3,$a0,$a1
            seq $s4,$a1,$zero
            sne $s5,$a1,$zero
            seq $s6,$a1,0
            sne $s7,$a1,0
        """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(target)
      val program = machine.assemble(listOf(file))
      val value = if (wide) 0x1_ffff_0001L else -65535L
      val mask = if (wide) 0x3_0000_0000L else 0L
      machine.editIntegerRegister(4, value)
      machine.editIntegerRegister(5, mask)
      machine.editIntegerRegister(19, value xor 1)
      assertEquals(if (wide) "or" else "addu", program.machineList!![7].instruction?.name)
      val instructionsAfterSelect = program.machineList!!.size - 12
      assertTrue(program.simulate(30))
      val expected =
          listOf(
              value and mask,
              value or mask,
              value xor mask,
              value.inv(),
              1L,
              value or 0xffffL,
              value xor 0xffffL,
              value,
              if (wide) value else 0L,
              value,
              if (wide) 0L else value,
              if (wide) value else 0L,
              if (wide) 0L else 1L,
              if (wide) 1L else 0L,
              if (wide) 0L else 1L,
              if (wide) 1L else 0L,
          )
      assertEquals(expected, (8..23).map(machine.registerFile::getLongValueNoNotify))
      repeat(instructionsAfterSelect + 1) { machine.backStepper.backStep() }
      assertEquals(value xor 1, machine.registerFile.getLongValueNoNotify(19))
    }
  }

  @ParameterizedTest
  @EnumSource(MarsTarget::class)
  fun `signed and unsigned comparisons drive control flow at register width`(
      target: MarsTarget,
      @TempDir directory: Path,
  ) {
    val file =
        Files.writeString(
            directory.resolve("branches.s"),
            $$"""
            slt $t0,$a0,$zero
            sltu $t1,$a0,$a1
            slti $t2,$a0,-1
            sltiu $t3,$a0,-1
            beq $a0,$zero,equal
            addiu $t4,$zero,1
            equal: bne $a1,$zero,nonzero
            addiu $t5,$zero,1
            nonzero: bltz $a0,negative
            addiu $t6,$zero,1
            negative: bgez $a0,done
            addiu $t7,$zero,1
            done: nop
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(target)
      machine.settings.setDelayedBranchingEnabled(false)
      val program = machine.assemble(listOf(file))
      val wide = target == MarsTarget.MARS64
      machine.editIntegerRegister(4, if (wide) Long.MIN_VALUE else 0L)
      machine.editIntegerRegister(5, if (wide) -0x1_0000_0000L else 0L)
      assertTrue(program.simulate(20))
      val expected = if (wide) listOf(1, 1, 1, 1, 1, 1, 1, 1) else listOf(0, 0, 0, 1, 0, 1, 1, 0)
      assertEquals(expected, (8..15).map(machine.registerFile::getValueNoNotify))
    }
  }

  @ParameterizedTest
  @CsvSource(
      $$"teq $a0,$zero | false",
      $$"tne $a0,$zero | true",
      $$"teqi $a0,0 | false",
      $$"tnei $a0,0 | true",
      $$"tge $a0,$zero | false",
      $$"tlt $a0,$zero | true",
      $$"tgei $a0,-1 | false",
      $$"tlti $a0,-1 | true",
      $$"tgeu $a0,$zero | true",
      $$"tltu $a0,$zero | false",
      $$"tgeiu $a0,-1 | false",
      $$"tltiu $a0,-1 | true",
      delimiter = '|',
  )
  fun `wide trap predicates use upper bits and sign extend immediates`(
      instruction: String,
      traps: Boolean,
      @TempDir directory: Path,
  ) {
    val file = Files.writeString(directory.resolve("trap.s"), "$instruction\nnop\n")
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(file))
      machine.editIntegerRegister(4, Long.MIN_VALUE)
      val operations = program.machineList!!.dropLast(1)
      val mnemonic = instruction.substringBefore(' ')
      val immediate = instruction.substringAfter(',').toIntOrNull()
      if (immediate != null) {
        val native =
            mnemonic.removeSuffix("iu").let {
              if (it != mnemonic) it + "u" else it.removeSuffix("i")
            }
        assertEquals(listOf("daddiu", native), operations.map { it.instruction?.name })
        val trapSelector =
            mapOf(
                "tgei" to 8,
                "tgeiu" to 9,
                "tlti" to 10,
                "tltiu" to 11,
                "teqi" to 12,
                "tnei" to 14,
            )
        val oldWord =
            0x04800000 or (trapSelector.getValue(mnemonic) shl 16) or (immediate and 0xffff)
        assertTrue(machine.instructionSet.findByBinaryCode(oldWord)?.name != mnemonic)
      }
      if (traps) {
        val failure = assertThrows<ProcessingException> { program.simulate(operations.size) }
        assertTrue(failure.message!!.contains("trap"))
      } else {
        assertFalse(program.simulate(operations.size))
      }
    }
  }

  @ParameterizedTest
  @CsvSource($$"$at", "$1")
  fun `immediate trap rejects an operand that would be overwritten by expansion`(
      register: String,
      @TempDir directory: Path,
  ) {
    val file = Files.writeString(directory.resolve("trap.s"), "teqi $register,0\n")
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val failure = assertThrows<ProcessingException> { machine.assemble(listOf(file)) }
      assertTrue(
          failure
              .errors()!!
              .generateErrorReport()
              .contains("Use a different temporary and a register trap"),
      )
    }
  }
}
