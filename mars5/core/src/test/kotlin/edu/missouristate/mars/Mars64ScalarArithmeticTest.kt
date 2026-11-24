package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.DIVIDE_BY_ZERO
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INEXACT
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INVALID_OPERATION
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Mars64ScalarArithmeticTest {
  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `sign and classification instructions are bit operations even for signaling NaNs`(
      double: Boolean,
      @TempDir directory: Path,
  ) {
    val format = if (double) "d" else "s"
    val source =
        Files.writeString(
            directory.resolve("sign.s"),
            $$"""
            ctc1 $8,$31
            abs.$$format $f3,$f5
            neg.$$format $f3,$f5
            mov.$$format $f3,$f5
            class.$$format $f3,$f5
        """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
      val base = if (double) 0x462028c0 else 0x460028c0
      assertEquals(
          listOf(5, 7, 6, 27).map { base or it },
          program.machineList!!.drop(1).map(ProgramStatement::binaryStatement),
      )
      val fpu = machine.coprocessor1
      val signaling = if (double) 0x7ff0_0000_0000_0123L else 0x7f80_0123L
      val negative = signaling or if (double) Long.MIN_VALUE else (1L shl 31)
      val status = (INVALID_OPERATION shl 7) or (DIVIDE_BY_ZERO shl 12) or (INEXACT shl 2)
      machine.editIntegerRegister(8, status)
      machine.editCoprocessor1Pair(5, negative)
      program.simulate(1)
      val original = fpu.floatingStatus
      for (expected in listOf(signaling, signaling, negative, 1L)) {
        program.simulate(1)
        assertEquals(expected, fpu.getDoublewordNoNotify(3))
        assertEquals(original, fpu.floatingStatus)
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `extrema preserve the first NaN payload and prefer numbers when invalid is masked`(
      double: Boolean,
      @TempDir directory: Path,
  ) {
    val format = if (double) "d" else "s"
    val source =
        Files.writeString(
            directory.resolve("extrema.s"),
            $$"""
            max.$$format $f3,$f5,$f7
            min.$$format $f3,$f5,$f7
            maxa.$$format $f3,$f5,$f7
            mina.$$format $f3,$f5,$f7
            ctc1 $8,$31
            max.$$format $f3,$f5,$f7
        """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
      val base = if (double) 0x462728c0 else 0x460728c0
      assertEquals(
          listOf(30, 28, 31, 29).map { base or it },
          program.machineList!!.take(4).map(ProgramStatement::binaryStatement),
      )
      val fpu = machine.coprocessor1
      val signaling = if (double) 0x7ff0_0000_0000_0123L else 0x7f80_0123L
      val quiet = if (double) 0x7ff8_0000_0000_0456L else 0x7fc0_0456L
      fun bits(value: Double) =
          if (double) value.toRawBits() else value.toFloat().toRawBits().toUInt().toLong()
      fun inputs(a: Long, b: Long) {
        machine.editCoprocessor1Pair(5, a)
        machine.editCoprocessor1Pair(7, b)
      }
      fun step(expected: Long, causes: Int = 0) {
        program.simulate(1)
        assertEquals(expected, fpu.getDoublewordNoNotify(3))
        assertEquals(causes, (fpu.floatingStatus ushr 12) and 31)
      }

      inputs(signaling, quiet)
      step(signaling, INVALID_OPERATION)
      inputs(quiet, bits(-0.0))
      step(bits(-0.0))
      inputs(bits(-2.0), bits(2.0))
      step(bits(2.0))
      inputs(bits(0.0), bits(-0.0))
      step(bits(-0.0))
      machine.editIntegerRegister(8, INVALID_OPERATION shl 7)
      program.simulate(1)
      inputs(bits(2.0), signaling)
      assertThrows(ProcessingException::class.java) { program.simulate(1) }
      assertEquals(bits(-0.0), fpu.getDoublewordNoNotify(3))
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `roots preserve signed zero and report IEEE exceptions`(
      double: Boolean,
      @TempDir directory: Path,
  ) {
    val format = if (double) "d" else "s"
    val source =
        Files.writeString(
            directory.resolve("roots.s"),
            $$"""
            ctc1 $8,$31
            sqrt.$$format $f3,$f5
            recip.$$format $f3,$f5
            rsqrt.$$format $f3,$f5
            sqrt.$$format $f3,$f5
            rsqrt.$$format $f3,$f5
        """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
      val base = if (double) 0x462028c0 else 0x460028c0
      assertEquals(
          listOf(4, 21, 22, 4, 22).map { base or it },
          program.machineList!!.drop(1).map(ProgramStatement::binaryStatement),
      )
      val fpu = machine.coprocessor1
      fun bits(value: Double) =
          if (double) value.toRawBits() else value.toFloat().toRawBits().toUInt().toLong()
      fun step(input: Double, expected: Long, causes: Int = 0) {
        machine.editCoprocessor1Pair(5, bits(input))
        program.simulate(1)
        assertEquals(expected, fpu.getDoublewordNoNotify(3))
        assertEquals(causes, (fpu.floatingStatus ushr 12) and 31)
      }

      machine.editIntegerRegister(8, 2)
      program.simulate(1)
      // Binary64's nearest sqrt(2) is already above the exact value. Binary32's is below it.
      step(2.0, bits(sqrt(2.0)) + if (double) 0 else 1, INEXACT)
      step(-0.0, bits(Double.NEGATIVE_INFINITY), DIVIDE_BY_ZERO)
      step(4.0, bits(0.5))
      step(-0.0, bits(-0.0))
      step(-1.0, bits(Double.NaN), INVALID_OPERATION)
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `arithmetic uses odd registers and rounds according to FCSR`(
      double: Boolean,
      @TempDir directory: Path,
  ) {
    val format = if (double) "d" else "s"
    val operations =
        listOf("add" to 0, "sub" to 1, "mul" to 2, "div" to 3, "maddf" to 24, "msubf" to 25)
    val source =
        Files.writeString(
            directory.resolve("scalar.s"),
            buildString {
              appendLine($$"ctc1 $8,$31")
              for ((name) in operations) appendLine($$"$$name.$$format $f3,$f5,$f7")
            },
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
      val base = if (double) 0x462728c0 else 0x460728c0
      assertEquals(
          operations.map { base or it.second },
          program.machineList!!.drop(1).map(ProgramStatement::binaryStatement),
      )
      val fpu = machine.coprocessor1
      fun bits(value: Double) =
          if (double) value.toRawBits() else value.toFloat().toRawBits().toUInt().toLong()
      fun write(left: Double, right: Double, previous: Double = 0.0) {
        machine.editCoprocessor1Pair(3, bits(previous))
        machine.editCoprocessor1Pair(5, bits(left))
        machine.editCoprocessor1Pair(7, bits(right))
      }
      fun step(expected: Long, causes: Int = 0) {
        program.simulate(1)
        assertEquals(expected, fpu.getDoublewordNoNotify(3))
        assertEquals(causes, (fpu.floatingStatus ushr 12) and 31)
      }

      machine.editIntegerRegister(8, 2)
      program.simulate(1)
      val next = bits(1.0) + 1
      write(1.0, Math.scalb(1.0, if (double) -53 else -24))
      step(next, INEXACT)
      write(4.0, 2.0)
      step(bits(2.0))
      write(-2.0, 3.0)
      step(bits(-6.0))
      write(1.0, 0.0)
      step(bits(Double.POSITIVE_INFINITY), DIVIDE_BY_ZERO)
      write(2.0, 3.0, 7.0)
      step(bits(13.0))
      write(2.0, 3.0, 7.0)
      step(bits(1.0))
      assertEquals(INEXACT or DIVIDE_BY_ZERO, (fpu.floatingStatus ushr 2) and 31)
      machine.backStepper.backStep()
      assertEquals(bits(7.0), fpu.getDoublewordNoNotify(3))
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `enabled invalid arithmetic preserves the destination`(
      double: Boolean,
      @TempDir directory: Path,
  ) {
    val format = if (double) "d" else "s"
    val source =
        Files.writeString(directory.resolve("invalid.s"), $$"ctc1 $8,$31\ndiv.$$format $f3,$f5,$f7")
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
      machine.editIntegerRegister(8, INVALID_OPERATION shl 7)
      machine.editCoprocessor1Pair(3, 99)
      program.simulate(1)
      assertThrows(ProcessingException::class.java) { program.simulate(1) }
      assertEquals(99L, machine.coprocessor1.getDoublewordNoNotify(3))
      assertEquals(INVALID_OPERATION, (machine.coprocessor1.floatingStatus ushr 12) and 31)
      assertEquals(0, (machine.coprocessor1.floatingStatus ushr 2) and 31)
    }
  }
}
