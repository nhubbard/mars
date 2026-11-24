package edu.missouristate.mars

import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.mips.hardware.Coprocessor1State
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.InstructionLoader
import java.lang.Double.MIN_NORMAL as DOUBLE_MIN_NORMAL
import java.lang.Float.MIN_NORMAL
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt
import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Pins supported MARS FP behavior, including its saturation conventions and opcode rounding. */
internal class FloatingPointEdgeTest {
  @ParameterizedTest
  @MethodSource("cases")
  fun specialValuesAndRoundingKeepTheirEstablishedSemantics(
      instruction: BasicInstruction,
      value: Double,
  ) {
    TestSessions.create().use { session ->
      val fp: Coprocessor1State = session.coprocessor1
      val context = InstructionContext(session, session.syscallEnvironment)
      val name: String = instruction.javaClass.simpleName.substring(5)
      val base: String = name.replaceFirst("[12]$".toRegex(), "")
      val sourceDouble = base.endsWith("D") || base == "CvtSD"
      val input = if (sourceDouble) value else value.toFloat().toDouble()
      if (sourceDouble) {
        fp.setRegisterPairToDouble(2, input)
        fp.setRegisterPairToDouble(4, 2.0)
      } else {
        fp.setRegisterToFloat(2, input.toFloat())
        fp.setRegisterToFloat(4, 2f)
      }
      val comparison: Boolean = base.matches("C(Eq|Le|Lt)[SD]".toRegex())
      val operands =
          if (comparison) if (name.endsWith("2")) intArrayOf(3, 2, 4) else intArrayOf(2, 4)
          else intArrayOf(8, 2, 4)
      val statement =
          ProgramStatement(
              null,
              instruction.exampleFormat,
              null,
              null,
              instruction,
              session.registerFile.getPC(),
              1,
          )
      operands.copyInto(requireNotNull(statement.operands))
      context.execute(statement)
      if (comparison) {
        val expected =
            if (base.startsWith("CEq")) input == 2.0
            else if (base.startsWith("CLe")) input <= 2 else input < 2
        Assertions.assertThat(fp.getConditionFlag(if (name.endsWith("2")) 3 else 0))
            .isEqualTo(if (expected) 1 else 0)
      } else if (base.startsWith("CvtW")) {
        Assertions.assertThat(fp.getValue(8)).isEqualTo(input.toInt())
      } else if (base.matches("(Floor|Ceil|Round|Trunc)W[SD]".toRegex())) {
        val invalid =
            !input.isFinite() ||
                input < Int.MIN_VALUE ||
                input >
                    (if (sourceDouble) Int.MAX_VALUE.toDouble()
                    else Int.MAX_VALUE.toFloat().toDouble())
        val expected =
            if (invalid) Int.MAX_VALUE
            else
                (if (base.startsWith("Floor")) floor(input)
                    else if (base.startsWith("Ceil")) ceil(input)
                    else if (base.startsWith("Round")) round(input) else input)
                    .toInt()
        Assertions.assertThat(fp.getValue(8)).isEqualTo(expected)
      } else {
        val expected =
            if (base.startsWith("Add")) input + 2
            else if (base.startsWith("Sub")) input - 2
            else if (base.startsWith("Mul")) input * 2
            else if (base.startsWith("Div")) input / 2
            else if (base.startsWith("Sqrt")) sqrt(input)
            else if (base.startsWith("Abs")) abs(input)
            else if (base.startsWith("Neg")) -input else input
        val targetDouble = if (base.startsWith("Cvt")) base[3] == 'D' else base.endsWith("D")
        if (targetDouble) {
          if (expected.isNaN()) {
            Assertions.assertThat(fp.getDoubleFromRegisterPair(8)).isNaN()
          } else {
            Assertions.assertThat(fp.getDoubleFromRegisterPair(8)).isEqualTo(expected)
          }
        } else {
          if (expected.isNaN()) {
            Assertions.assertThat(fp.getFloatFromRegister(8)).isNaN()
          } else {
            Assertions.assertThat(fp.getFloatFromRegister(8)).isEqualTo(expected.toFloat())
          }
        }
      }
    }
  }

  companion object {
    @JvmStatic
    fun cases(): List<Arguments> {
      val values =
          doubleArrayOf(
              Double.NaN,
              Double.POSITIVE_INFINITY,
              Double.NEGATIVE_INFINITY,
              DOUBLE_MIN_NORMAL,
              Double.MIN_VALUE,
              MIN_NORMAL.toDouble(),
              Float.MIN_VALUE.toDouble(),
              0.0,
              -0.0,
              2.5,
              -2.5,
              3.5,
              -3.5,
              1e20,
          )
      return InstructionLoader()
          .loadBasicInstructions()
          .filter { instruction: BasicInstruction ->
            instruction.javaClass.simpleName.matches(
                "Instr((Add|Sub|Mul|Div|Sqrt|Abs|Neg|Mov|FloorW|CeilW|RoundW|TruncW)[SD]|Cvt(SD|DS|WS|WD)|C(Eq|Le|Lt)[SD][12])"
                    .toRegex(),
            )
          }
          .flatMap { instruction: BasicInstruction ->
            values.map { value -> Arguments.of(instruction, value) }
          }
    }
  }
}
