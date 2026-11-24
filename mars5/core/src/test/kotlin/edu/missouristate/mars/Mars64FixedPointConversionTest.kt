package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Mars64FixedPointConversionTest {
  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `fixed-point conversions assemble and use the specified rounding mode`(
      double: Boolean,
      @TempDir directory: Path,
  ) {
    val format = if (double) "d" else "s"
    val conversions = listOf("round" to 8, "trunc" to 9, "ceil" to 10, "floor" to 11, "cvt" to 37)
    val source =
        Files.writeString(
            directory.resolve("conversion.s"),
            buildString {
              for (mode in 0..3) {
                appendLine($$"ctc1 $8,$31")
                for ((name) in conversions) {
                  appendLine($$"$$name.w.$$format $f31,$f3")
                  appendLine($$"$$name.l.$$format $f31,$f3")
                }
              }
            },
        )
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(source), null, extended = false, warnings = false)
      val fpu = session.coprocessor1
      val input = if (double) (-2.5).toRawBits() else (-2.5f).toRawBits().toUInt().toLong()
      val fixed = listOf(-2L, -2L, -2L, -3L)
      session.editCoprocessor1Pair(3, input)
      for (mode in 0..3) {
        session.editIntegerRegister(8, mode)
        program.simulate(1)
        for ((index, conversion) in conversions.withIndex()) {
          val expected = fixed[if (index == 4) mode else index]
          val base = if (double) 0x46201fc0 else 0x46001fc0
          val position = mode * 11 + 1 + index * 2
          val wordOperation = if (index == 4) 36 else conversion.second + 4
          assertEquals(base or wordOperation, program.machineList!![position].binaryStatement)
          assertEquals(
              base or conversion.second,
              program.machineList!![position + 1].binaryStatement,
          )
          program.simulate(1)
          assertEquals(expected, fpu.getValue(31).toLong())
          program.simulate(1)
          assertEquals(expected, fpu.getDoublewordNoNotify(31))
          assertEquals(0x000c1004 or mode, fpu.floatingStatus)
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `round to integral keeps signed zero and quiets a signaling NaN`(
      double: Boolean,
      @TempDir directory: Path,
  ) {
    val format = if (double) "d" else "s"
    val source =
        Files.writeString(
            directory.resolve("rint.s"),
            $$"""
            rint.$$format $f31,$f3
            ctc1 $8,$31
            rint.$$format $f31,$f3
            rint.$$format $f31,$f3
            rint.$$format $f31,$f3
        """
                .trimIndent(),
        )
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(source), null, extended = false, warnings = false)
      val fpu = session.coprocessor1
      val word = if (double) 0x46201fda else 0x46001fda
      assertEquals(word, program.machineList!!.first().binaryStatement)
      fun bits(value: Double): Long =
          if (double) value.toRawBits() else value.toFloat().toRawBits().toUInt().toLong()
      session.editCoprocessor1Pair(3, bits(-0.5))
      session.editIntegerRegister(8, 3)
      program.simulate(1)
      assertEquals(bits(-0.0), fpu.getDoublewordNoNotify(31))
      program.simulate(2)
      assertEquals(bits(-1.0), fpu.getDoublewordNoNotify(31))
      assertEquals(0x000c1007, fpu.floatingStatus)
      val signaling = if (double) 0x7ff0_0000_0000_0123L else 0x7f80_0123L
      val quiet = signaling or (if (double) 1L shl 51 else 1L shl 22)
      session.editCoprocessor1Pair(3, signaling)
      program.simulate(1)
      assertEquals(quiet, fpu.getDoublewordNoNotify(31))
      assertEquals(0x000d0047, fpu.floatingStatus)
      session.editCoprocessor1Pair(3, bits(Double.POSITIVE_INFINITY))
      program.simulate(1)
      assertEquals(bits(Double.POSITIVE_INFINITY), fpu.getDoublewordNoNotify(31))
      assertEquals(0x000c0047, fpu.floatingStatus)
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `conversion handles limits and NaNs and an inexact trap suppresses the result`(
      longword: Boolean,
      @TempDir directory: Path,
  ) {
    val prefix = if (longword) "cvt.l.d" else "cvt.w.d"
    val limit = if (longword) -Long.MIN_VALUE.toDouble() else -Int.MIN_VALUE.toDouble()
    val maximum = if (longword) Long.MAX_VALUE else Int.MAX_VALUE.toLong()
    val minimum = if (longword) Long.MIN_VALUE else Int.MIN_VALUE.toLong()
    val source =
        Files.writeString(
            directory.resolve("limits.s"),
            buildString {
              repeat(4) { appendLine($$"$$prefix $f31,$f3") }
              appendLine($$"ctc1 $8,$31")
              appendLine($$"$$prefix $f31,$f3")
            },
        )
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(source), null, extended = false, warnings = false)
      val fpu = session.coprocessor1
      fun result() = if (longword) fpu.getDoublewordNoNotify(31) else fpu.getValue(31).toLong()
      val inputs =
          listOf(
              -limit to minimum,
              limit to maximum,
              Double.NEGATIVE_INFINITY to minimum,
              Double.NaN to 0L,
          )
      for ((index, pair) in inputs.withIndex()) {
        session.editCoprocessor1Pair(3, pair.first.toRawBits())
        program.simulate(1)
        assertEquals(pair.second, result())
        assertEquals(if (index == 0) 0x000c0000 else 0x000d0040, fpu.floatingStatus)
      }
      session.editIntegerRegister(8, 0x80)
      session.editCoprocessor1Pair(3, 3.5.toRawBits())
      program.simulate(1)
      assertThrows(ProcessingException::class.java) { program.simulate(1) }
      assertEquals(0L, result())
      assertEquals(0x000c1080, fpu.floatingStatus)
    }
  }
}
