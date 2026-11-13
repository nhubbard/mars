package edu.missouristate.mars;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;

import edu.missouristate.mars.core.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.*;
import edu.missouristate.mars.simulator.Exceptions;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Architectural test vectors: expected results are specified independently of instruction code. */
class BasicInstructionSemanticsTest {
  static Stream<BasicInstruction> instructions() {
    return new InstructionLoader().loadBasicInstructions().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("instructions")
  void executesDefinedSemanticsAndReversesHardwareChanges(BasicInstruction instruction)
      throws Exception {
    try (var session = TestSessions.create()) {
      var context = new InstructionContext(session, session.syscallEnvironment);
      var registers = session.registerFile;
      var floating = session.coprocessor1;
      int pc = registers.getPC(), address = session.memory.dataBaseAddress;
      registers.updateRegister(2, 10);
      registers.updateRegister(4, 3);
      registers.updateRegister(33, 1);
      registers.updateRegister(34, 2);
      session.memory.setWord(address, 0x89abcdef);
      session.memory.setWord(address + 4, 0x01234567);
      String name = instruction.getClass().getSimpleName().substring(5);
      String base =
          name.matches("(Mov[ft]([SD])?|Jalr|Break|Bc1[tf]|C(Eq|Le|Lt)[SD])[12]")
              ? name.substring(0, name.length() - 1)
              : name;
      int[] operands = {8, 2, 4};
      Consumer<MarsContext> verify;
      Integer exception = null;
      Integer result =
          switch (base) {
            case "Add", "Addu" -> 13;
            case "Sub", "Subu" -> 7;
            case "Addi", "Addiu", "Ori", "Xori" -> 14;
            case "And" -> 2;
            case "Or" -> 11;
            case "Xor" -> 9;
            case "Nor" -> ~11;
            case "Andi", "Slt", "Sltu", "Slti", "Sltiu", "Srl", "Sra" -> 0;
            case "Sll" -> 160;
            case "Sllv" -> 80;
            case "Srlv", "Srav" -> 1;
            case "Mul" -> 30;
            default -> null;
          };
      if (result != null) {
        int expected = result;
        verify = s -> assertThat(s.registerFile.getValue(8)).isEqualTo(expected);
      } else if (Set.of("Mult", "Multu", "Madd", "Maddu", "Msub", "Msubu", "Div", "Divu")
          .contains(base)) {
        operands = new int[] {2, 4};
        int expectedHi =
            switch (base) {
              case "Madd", "Maddu", "Div", "Divu" -> 1;
              default -> 0;
            };
        int expectedLo =
            switch (base) {
              case "Mult", "Multu" -> 30;
              case "Madd", "Maddu" -> 32;
              case "Msub", "Msubu" -> -28;
              default -> 3;
            };
        verify =
            s -> {
              assertThat(s.registerFile.getValue(33)).isEqualTo(expectedHi);
              assertThat(s.registerFile.getValue(34)).isEqualTo(expectedLo);
            };
      } else if (Set.of("Mfhi", "Mflo", "Mthi", "Mtlo").contains(base)) {
        operands = new int[] {8};
        registers.updateRegister(8, 10);
        int target = base.equals("Mthi") ? 33 : base.equals("Mtlo") ? 34 : 8;
        int expected = base.equals("Mfhi") ? 1 : base.equals("Mflo") ? 2 : 10;
        verify = s -> assertThat(s.registerFile.getValue(target)).isEqualTo(expected);
      } else if (base.equals("Lui")) {
        operands = new int[] {8, 0xabcd};
        verify = s -> assertThat(s.registerFile.getValue(8)).isEqualTo(0xabcd0000);
      } else if (Set.of("Clo", "Clz").contains(base)) {
        operands = new int[] {8, 2};
        registers.updateRegister(2, base.equals("Clo") ? 0xff000000 : 0x00ffffff);
        verify = s -> assertThat(s.registerFile.getValue(8)).isEqualTo(8);
      } else if (Set.of("Movn", "Movz", "Movf", "Movt").contains(base)) {
        if (base.equals("Movz")) {
          registers.updateRegister(4, 0);
        }
        if (base.equals("Movt")) {
          floating.setConditionFlag(name.endsWith("2") ? 4 : 0);
        }
        verify = s -> assertThat(s.registerFile.getValue(8)).isEqualTo(10);
      } else if (Set.of("Beq", "Bne", "Bgez", "Bgezal", "Bgtz", "Blez", "Bltz", "Bltzal")
          .contains(base)) {
        boolean taken = !Set.of("Blez", "Bltz", "Bltzal").contains(base);
        if (base.equals("Beq")) {
          registers.updateRegister(4, 10);
        }
        operands =
            base.equals("Beq") || base.equals("Bne") ? new int[] {2, 4, 2} : new int[] {2, 2};
        verify =
            s -> {
              assertThat(s.registerFile.getPC()).isEqualTo(pc + (taken ? 12 : 4));
              if (base.equals("Bgezal")) {
                assertThat(s.registerFile.getValue(31)).isEqualTo(pc + 4);
              }
            };
      } else if (Set.of("J", "Jal", "Jr", "Jalr").contains(base)) {
        registers.updateRegister(2, pc + 32);
        operands =
            base.equals("J") || base.equals("Jal")
                ? new int[] {(pc + 32) >>> 2}
                : base.equals("Jalr") && name.endsWith("1") ? new int[] {8, 2} : new int[] {2};
        verify =
            s -> {
              assertThat(s.registerFile.getPC()).isEqualTo(pc + 32);
              if (base.equals("Jal")) {
                assertThat(s.registerFile.getValue(31)).isEqualTo(pc + 4);
              }
              if (base.equals("Jalr")) {
                assertThat(s.registerFile.getValue(name.endsWith("1") ? 8 : 31)).isEqualTo(pc + 4);
              }
            };
      } else if (Set.of(
              "Lw", "Ll", "Lwl", "Lwr", "Lb", "Lbu", "Lh", "Lhu", "Sw", "Sc", "Swl", "Swr", "Sb",
              "Sh", "Lwc1", "Ldc1", "Swc1", "Sdc1")
          .contains(base)) {
        registers.updateRegister(2, address);
        registers.updateRegister(8, 0x12345678);
        floating.setRegisterToInt(8, 0x12345678);
        floating.setRegisterToInt(9, 0x76543210);
        operands = new int[] {8, base.equals("Lwl") || base.equals("Swl") ? 3 : 0, 2};
        Integer expectedLoad =
            switch (base) {
              case "Lw", "Ll", "Lwl", "Lwr" -> 0x89abcdef;
              case "Lb" -> -17;
              case "Lbu" -> 239;
              case "Lh" -> -12817;
              case "Lhu" -> 52719;
              default -> null;
            };
        verify =
            s -> {
              if (expectedLoad != null) {
                assertThat(s.registerFile.getValue(8)).isEqualTo(expectedLoad);
              } else {
                switch (base) {
                  case "Lwc1", "Ldc1" -> {
                    assertThat(s.coprocessor1.getValue(8)).isEqualTo(0x89abcdef);
                    if (base.equals("Ldc1")) {
                      assertThat(s.coprocessor1.getValue(9)).isEqualTo(0x01234567);
                    }
                  }
                  default -> {
                    int expectedWord =
                        base.equals("Sb")
                            ? 0x89abcd78
                            : base.equals("Sh") ? 0x89ab5678 : 0x12345678;
                    try {
                      assertThat(s.memory.getWord(address)).isEqualTo(expectedWord);
                      if (base.equals("Sdc1")) {
                        assertThat(s.memory.getWord(address + 4)).isEqualTo(0x76543210);
                      }
                    } catch (AddressErrorException e) {
                      throw new AssertionError(e);
                    }
                    if (base.equals("Sc")) {
                      assertThat(s.registerFile.getValue(8)).isEqualTo(1);
                    }
                  }
                }
              }
            };
      } else if (base.equals("Mfc0") || base.equals("Mtc0")) {
        operands = new int[] {8, Coprocessor0State.EPC};
        session.coprocessor0.updateRegister(Coprocessor0State.EPC, 42);
        registers.updateRegister(8, 99);
        verify =
            s ->
                assertThat(
                        base.equals("Mfc0")
                            ? s.registerFile.getValue(8)
                            : s.coprocessor0.getValue(Coprocessor0State.EPC))
                    .isEqualTo(base.equals("Mfc0") ? 42 : 99);
      } else if (base.equals("Mfc1") || base.equals("Mtc1")) {
        operands = new int[] {8, 2};
        floating.setRegisterToInt(2, 42);
        registers.updateRegister(8, 99);
        verify =
            s ->
                assertThat(
                        base.equals("Mfc1")
                            ? s.registerFile.getValue(8)
                            : s.coprocessor1.getValue(2))
                    .isEqualTo(base.equals("Mfc1") ? 42 : 99);
      } else if (base.equals("Bc1t") || base.equals("Bc1f")) {
        int flag = name.endsWith("2") ? 4 : 0;
        if (base.equals("Bc1t")) {
          floating.setConditionFlag(flag);
        }
        operands = name.endsWith("2") ? new int[] {flag, 2} : new int[] {2};
        verify = s -> assertThat(s.registerFile.getPC()).isEqualTo(pc + 12);
      } else if (base.startsWith("CEq") || base.startsWith("CLe") || base.startsWith("CLt")) {
        boolean doubles = base.endsWith("D");
        int flag = name.endsWith("2") ? 4 : 0;
        if (doubles) {
          floating.setRegisterPairToDouble(2, 2);
          floating.setRegisterPairToDouble(4, 4);
        } else {
          floating.setRegisterToFloat(2, 2);
          floating.setRegisterToFloat(4, 4);
        }
        operands = name.endsWith("2") ? new int[] {flag, 2, 4} : new int[] {2, 4};
        verify =
            s ->
                assertThat(s.coprocessor1.getConditionFlag(flag))
                    .isEqualTo(base.startsWith("CEq") ? 0 : 1);
      } else if (base.endsWith("S") || base.endsWith("D") || base.startsWith("Cvt")) {
        boolean doubles = base.endsWith("D");
        if (doubles) {
          floating.setRegisterPairToDouble(2, 4.75);
          floating.setRegisterPairToDouble(4, 2);
        } else {
          floating.setRegisterToFloat(2, 4.75f);
          floating.setRegisterToFloat(4, 2);
        }
        operands = new int[] {8, 2, 4};
        String operation = base.substring(0, base.length() - 1);
        if (base.startsWith("Cvt")) {
          switch (base) {
            case "CvtDS" -> {
              floating.setRegisterToFloat(2, 4.75f);
              verify = s -> assertDouble(s, 4.75);
            }
            case "CvtDW" -> {
              floating.setRegisterToInt(2, 42);
              verify = s -> assertDouble(s, 42);
            }
            case "CvtSD" -> {
              floating.setRegisterPairToDouble(2, 4.75);
              verify = s -> assertThat(s.coprocessor1.getFloatFromRegister(8)).isEqualTo(4.75f);
            }
            case "CvtSW" -> {
              floating.setRegisterToInt(2, 42);
              verify = s -> assertThat(s.coprocessor1.getFloatFromRegister(8)).isEqualTo(42f);
            }
            case "CvtWD", "CvtWS" ->
                verify = s -> assertThat(s.coprocessor1.getValue(8)).isEqualTo(4);
            default -> throw new AssertionError(base);
          }
        } else if (operation.startsWith("Mov")) {
          if (operation.equals("Movt")) {
            floating.setConditionFlag(name.endsWith("2") ? 4 : 0);
          }
          if (operation.equals("Movz")) {
            registers.updateRegister(4, 0);
          }
          verify =
              s -> {
                if (doubles) {
                  assertDouble(s, 4.75);
                } else {
                  assertThat(s.coprocessor1.getFloatFromRegister(8)).isEqualTo(4.75f);
                }
              };
        } else if (Set.of("FloorW", "CeilW", "RoundW", "TruncW").contains(operation)) {
          int expected = operation.equals("FloorW") || operation.equals("TruncW") ? 4 : 5;
          verify = s -> assertThat(s.coprocessor1.getValue(8)).isEqualTo(expected);
        } else {
          double expected =
              switch (operation) {
                case "Add" -> 6.75;
                case "Sub" -> 2.75;
                case "Mul" -> 9.5;
                case "Div" -> 2.375;
                case "Sqrt" -> Math.sqrt(4.75);
                case "Abs" -> 4.75;
                case "Neg" -> -4.75;
                default -> throw new AssertionError("Missing vector for " + name);
              };
          verify =
              s -> {
                if (doubles) {
                  assertDouble(s, expected);
                } else {
                  assertThat(s.coprocessor1.getFloatFromRegister(8)).isEqualTo((float) expected);
                }
              };
        }
      } else if (base.startsWith("T")) {
        operands = new int[] {2, base.endsWith("i") || base.endsWith("iu") ? 3 : 4};
        if (base.startsWith("Teq")) {
          registers.updateRegister(2, 3);
        }
        boolean traps = !base.startsWith("Tlt");
        exception = traps ? Exceptions.TRAP_EXCEPTION : null;
        verify = s -> assertThat(s.registerFile.getValue(8)).isZero();
      } else if (base.equals("Break")) {
        operands = new int[] {0};
        exception = Exceptions.BREAKPOINT_EXCEPTION;
        verify = s -> assertThat(s.coprocessor0.getValue(Coprocessor0State.EPC)).isEqualTo(pc);
      } else if (base.equals("Syscall")) {
        registers.updateRegister(2, 10);
        exception = -1;
        verify = _ -> assertThat(context.getExitCode()).isZero();
      } else if (base.equals("Eret")) {
        session.coprocessor0.updateRegister(Coprocessor0State.EPC, pc + 32);
        session.coprocessor0.updateRegister(Coprocessor0State.STATUS, 3);
        verify =
            s -> {
              assertThat(s.registerFile.getPC()).isEqualTo(pc + 32);
              assertThat(s.coprocessor0.getValue(Coprocessor0State.STATUS)).isEqualTo(1);
            };
      } else {
        if (base.equals("Nop")) {
          verify = s -> assertThat(s.registerFile.getPC()).isEqualTo(pc + 4);
        } else {
          throw new AssertionError("Missing semantic vector for " + name);
        }
      }

      int[] beforeRegisters =
          Arrays.stream(registers.getRegisters()).mapToInt(Register::getValue).toArray();
      int[] beforeFloating =
          Arrays.stream(floating.getRegisters()).mapToInt(Register::getValue).toArray();
      int[] beforeCp0 =
          Arrays.stream(session.coprocessor0.getRegisters()).mapToInt(Register::getValue).toArray();
      int beforeFlags = floating.getConditionFlags();
      var statement =
          new ProgramStatement(
              null, instruction.getExampleFormat(), null, null, instruction, pc, 1);
      System.arraycopy(operands, 0, requireNonNull(statement.getOperands()), 0, operands.length);
      if (exception == null) {
        context.execute(statement);
      } else {
        var failure = catchThrowable(() -> context.execute(statement));
        assertThat(failure).isInstanceOf(ProcessingException.class);
        if (exception == -1) {
          assertThat(((ProcessingException) failure).errors()).isNull();
        } else {
          assertThat((session.coprocessor0.getValue(Coprocessor0State.CAUSE) >>> 2) & 31)
              .isEqualTo(exception);
        }
      }
      verify.accept(session);
      assertThat(registers.getValue(0)).isZero();
      session.backStepper.backStep();
      assertThat(Arrays.stream(registers.getRegisters()).mapToInt(Register::getValue).toArray())
          .containsExactly(beforeRegisters);
      assertThat(Arrays.stream(floating.getRegisters()).mapToInt(Register::getValue).toArray())
          .containsExactly(beforeFloating);
      assertThat(
              Arrays.stream(session.coprocessor0.getRegisters())
                  .mapToInt(Register::getValue)
                  .toArray())
          .containsExactly(beforeCp0);
      assertThat(floating.getConditionFlags()).isEqualTo(beforeFlags);
      assertThat(registers.getPC()).isEqualTo(pc);
      assertThat(session.memory.getWord(address)).isEqualTo(0x89abcdef);
      assertThat(session.memory.getWord(address + 4)).isEqualTo(0x01234567);
    }
  }

  private static void assertDouble(MarsContext session, double expected) {
    try {
      assertThat(session.coprocessor1.getDoubleFromRegisterPair(8)).isEqualTo(expected);
    } catch (InvalidRegisterAccessException error) {
      throw new AssertionError(error);
    }
  }
}
