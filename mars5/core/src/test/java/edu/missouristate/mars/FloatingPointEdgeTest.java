package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.*;

import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.mips.instructions.*;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins supported MARS FP behavior, including its saturation conventions and opcode rounding. */
class FloatingPointEdgeTest {
  static Stream<Arguments> cases() {
    double[] values = {
      Double.NaN,
      Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      Double.MIN_NORMAL,
      Double.MIN_VALUE,
      Float.MIN_NORMAL,
      Float.MIN_VALUE,
      0.0,
      -0.0,
      2.5,
      -2.5,
      3.5,
      -3.5,
      1e20
    };
    return InstructionRegistry.getAll().stream()
        .filter(
            instruction ->
                instruction
                    .getClass()
                    .getSimpleName()
                    .matches(
                        "Instr((Add|Sub|Mul|Div|Sqrt|Abs|Neg|Mov|FloorW|CeilW|RoundW|TruncW)[SD]|Cvt(SD|DS|WS|WD)|C(Eq|Le|Lt)[SD][12])"))
        .flatMap(
            instruction ->
                Arrays.stream(values).mapToObj(value -> Arguments.of(instruction, value)));
  }

  @ParameterizedTest
  @MethodSource("cases")
  void specialValuesAndRoundingKeepTheirEstablishedSemantics(
      BasicInstruction instruction, double value) throws Exception {
    try (var session = TestSessions.create()) {
      var fp = session.coprocessor1;
      var context = new InstructionContext(session, session.syscallEnvironment);
      String name = instruction.getClass().getSimpleName().substring(5);
      String base = name.replaceFirst("[12]$", "");
      boolean sourceDouble = base.endsWith("D") || base.equals("CvtSD");
      double input = sourceDouble ? value : (double) (float) value;
      if (sourceDouble) {
        fp.setRegisterPairToDouble(2, input);
        fp.setRegisterPairToDouble(4, 2);
      } else {
        fp.setRegisterToFloat(2, (float) input);
        fp.setRegisterToFloat(4, 2);
      }
      boolean comparison = base.matches("C(Eq|Le|Lt)[SD]");
      int[] operands =
          comparison
              ? name.endsWith("2") ? new int[] {3, 2, 4} : new int[] {2, 4}
              : new int[] {8, 2, 4};
      var statement =
          new ProgramStatement(
              null,
              instruction.getExampleFormat(),
              null,
              null,
              instruction,
              session.registerFile.getPC(),
              1);
      System.arraycopy(operands, 0, statement.getOperands(), 0, operands.length);
      context.execute(statement);
      if (comparison) {
        boolean expected =
            base.startsWith("CEq") ? input == 2 : base.startsWith("CLe") ? input <= 2 : input < 2;
        assertThat(fp.getConditionFlag(name.endsWith("2") ? 3 : 0)).isEqualTo(expected ? 1 : 0);
      } else if (base.startsWith("CvtW")) {
        assertThat(fp.getValue(8)).isEqualTo((int) input);
      } else if (base.matches("(Floor|Ceil|Round|Trunc)W[SD]")) {
        boolean invalid =
            !Double.isFinite(input)
                || input < Integer.MIN_VALUE
                || input > (sourceDouble ? Integer.MAX_VALUE : (double) (float) Integer.MAX_VALUE);
        int expected =
            invalid
                ? Integer.MAX_VALUE
                : (int)
                    (base.startsWith("Floor")
                        ? Math.floor(input)
                        : base.startsWith("Ceil")
                            ? Math.ceil(input)
                            : base.startsWith("Round") ? Math.rint(input) : input);
        assertThat(fp.getValue(8)).isEqualTo(expected);
      } else {
        double expected =
            base.startsWith("Add")
                ? input + 2
                : base.startsWith("Sub")
                    ? input - 2
                    : base.startsWith("Mul")
                        ? input * 2
                        : base.startsWith("Div")
                            ? input / 2
                            : base.startsWith("Sqrt")
                                ? Math.sqrt(input)
                                : base.startsWith("Abs")
                                    ? Math.abs(input)
                                    : base.startsWith("Neg") ? -input : input;
        boolean targetDouble = base.startsWith("Cvt") ? base.charAt(3) == 'D' : base.endsWith("D");
        if (targetDouble) {
          if (Double.isNaN(expected)) {
            assertThat(fp.getDoubleFromRegisterPair(8)).isNaN();
          } else {
            assertThat(fp.getDoubleFromRegisterPair(8)).isEqualTo(expected);
          }
        } else {
          if (Double.isNaN(expected)) {
            assertThat(fp.getFloatFromRegister(8)).isNaN();
          } else {
            assertThat(fp.getFloatFromRegister(8)).isEqualTo((float) expected);
          }
        }
      }
    }
  }
}
