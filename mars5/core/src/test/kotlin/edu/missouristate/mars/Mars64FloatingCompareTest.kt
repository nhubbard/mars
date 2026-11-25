package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.simulator.Exceptions.FLOATING_POINT_EXCEPTION
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Mars64FloatingCompareTest {
  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `all comparison predicates give the specified truth masks and instruction words`(
      double: Boolean,
      @TempDir directory: Path,
  ) {
    // Results ordered by less, equal, greater, unordered.
    val predicates =
        listOf(
            Triple("af", 0, "0000"),
            Triple("un", 1, "0001"),
            Triple("eq", 2, "0100"),
            Triple("ueq", 3, "0101"),
            Triple("lt", 4, "1000"),
            Triple("ult", 5, "1001"),
            Triple("le", 6, "1100"),
            Triple("ule", 7, "1101"),
            Triple("saf", 8, "0000"),
            Triple("sun", 9, "0001"),
            Triple("seq", 10, "0100"),
            Triple("sueq", 11, "0101"),
            Triple("slt", 12, "1000"),
            Triple("sult", 13, "1001"),
            Triple("sle", 14, "1100"),
            Triple("sule", 15, "1101"),
            Triple("or", 17, "1110"),
            Triple("une", 18, "1011"),
            Triple("ne", 19, "1010"),
            Triple("sor", 25, "1110"),
            Triple("sune", 26, "1011"),
            Triple("sne", 27, "1010"),
        )
    val format = if (double) "d" else "s"
    val source =
        Files.writeString(
            directory.resolve("compare.s"),
            buildString {
              for ((name) in predicates) repeat(4) {
                appendLine($$"cmp.$$name.$$format $f31,$f3,$f5")
              }
            },
        )
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(source), null, extended = false, warnings = false)
      val base = if (double) 0x46a51fc0 else 0x46851fc0
      for (reserved in listOf(16, 20, 21, 22, 23, 24, 28, 29, 30, 31)) {
        assertNull(session.instructionSet.findByBinaryCode(base or reserved))
      }
      assertNull(session.instructionSet.findByBinaryCode(0x46000032))
      fun bits(value: Double) =
          if (double) value.toRawBits() else value.toFloat().toRawBits().toUInt().toLong()
      val operands =
          listOf(-1.0 to 2.0, -0.0 to 0.0, Double.POSITIVE_INFINITY to 2.0, 1.0 to Double.NaN)
      for ((index, entry) in predicates.withIndex()) {
        val (_, code, truth) = entry
        for ((relation, values) in operands.withIndex()) {
          val statement = program.machineList!![index * 4 + relation]
          assertEquals(base or code, statement.binaryStatement)
          val decoded =
              ProgramStatement(statement.binaryStatement, statement.address, session.instructionSet)
          assertEquals(listOf(31, 3, 5), decoded.operands.take(3))
          session.editCoprocessor1Pair(3, bits(values.first))
          session.editCoprocessor1Pair(5, bits(values.second))
          program.simulate(1)
          val actual = session.coprocessor1.getDoublewordNoNotify(31)
          val tested = if (double) actual else actual.toInt().toLong()
          assertEquals(if (truth[relation] == '1') -1L else 0L, tested)
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `an enabled exception suppresses a comparison result but retains the cause`(
      compare: Boolean,
      @TempDir directory: Path,
  ) {
    val second = if (compare) $$"cmp.seq.s $f31,$f3,$f5" else $$"ctc1 $t1,$26"
    val source = Files.writeString(directory.resolve("trap.s"), $$"ctc1 $t0,$31\n$$second\n")
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(source), null, extended = false, warnings = false)
      session.editIntegerRegister(8, 0x0800)
      session.editIntegerRegister(9, 0x10000)
      session.editCoprocessor1Pair(3, 0x7fc0_0001L)
      session.editCoprocessor1Pair(31, 123L)
      program.simulate(1)
      assertThrows<ProcessingException> { program.simulate(1) }
      assertEquals(FLOATING_POINT_EXCEPTION, session.coprocessor0.getValue(CAUSE) ushr 2 and 31)
      assertEquals(0x000d0800, session.coprocessor1.floatingStatus)
      assertEquals(123L, session.coprocessor1.getDoublewordNoNotify(31))
      session.backStepper.backStep()
      assertEquals(0x000c0800, session.coprocessor1.floatingStatus)
    }
  }

  @Test
  fun `control aliases share status and backstep restores the per-operation cause`(
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("status.s"),
            $$"""
            cfc1 $t0,$31
            cmp.eq.d $f31,$f3,$f5
            cfc1 $t1,$26
            cmp.eq.d $f31,$f5,$f5
            cfc1 $t2,$31
            ctc1 $t3,$28
            cfc1 $t4,$28
            cmp.eq.s $f31,$f7,$f9
            """
                .trimIndent(),
        )
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(source), null, extended = false, warnings = false)
      session.editCoprocessor1Pair(3, 0x7ff0_0000_0000_0001L)
      session.editCoprocessor1Pair(5, 1.0.toRawBits())
      session.editCoprocessor1Pair(7, 1L)
      session.editIntegerRegister(11, 7)
      program.simulate(3)
      assertEquals(0x000c0000, session.registerFile.getValueNoNotify(8))
      assertEquals(0x10040, session.registerFile.getValueNoNotify(9))
      program.simulate(2)
      assertEquals(0x000c0040, session.registerFile.getValueNoNotify(10))
      session.backStepper.backStep()
      session.backStepper.backStep()
      assertEquals(0x000d0040, session.coprocessor1.floatingStatus)

      program.simulate(5)
      assertEquals(7, session.registerFile.getValueNoNotify(12))
      assertEquals(-1L, session.coprocessor1.getDoublewordNoNotify(31))
    }
  }

  @ParameterizedTest
  @ValueSource(strings = [$$"cfc1 $t0,$25", $$"ctc1 $t0,$0", $$"ctc1 $t0,$25"])
  fun `removed control registers and writes to FIR are reserved`(
      code: String,
      @TempDir directory: Path,
  ) {
    val source = Files.writeString(directory.resolve("reserved.s"), code)
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(source), null, extended = false, warnings = false)
      assertThrows<ProcessingException> { program.simulate(1) }
      assertEquals(
          RESERVED_INSTRUCTION_EXCEPTION,
          session.coprocessor0.getValue(CAUSE) ushr 2 and 31,
      )
    }
  }
}
