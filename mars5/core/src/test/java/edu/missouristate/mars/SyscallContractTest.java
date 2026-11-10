package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.*;

import edu.missouristate.mars.api.SyscallEnvironment;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.mips.instructions.SyscallRegistry;
import edu.missouristate.mars.mips.instructions.syscalls.Syscall;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SyscallContractTest {
  static Stream<Integer> services() {
    return new SyscallRegistry().services().stream().map(Syscall::getNumber).sorted();
  }

  @ParameterizedTest
  @MethodSource("services")
  void everySupportedServiceUsesItsSessionAndEnvironment(int number) throws Exception {
    try (var session = TestSessions.create()) {
      var calls = new ArrayList<String>();
      long wallTime = 0x0123456789abcdefL;
      var environment =
          (SyscallEnvironment)
              Proxy.newProxyInstance(
                  SyscallEnvironment.class.getClassLoader(),
                  new Class<?>[] {SyscallEnvironment.class},
                  (proxy, method, args) -> {
                    calls.add(
                        method.getName() + Arrays.toString(args == null ? new Object[0] : args));
                    return switch (method.getName()) {
                      case "readInt" -> 7;
                      case "readFloat" -> 1.5f;
                      case "readDouble" -> 2.5;
                      case "readChar" -> 'Z';
                      case "readString" -> "hi";
                      case "showInputDialog" -> "42";
                      case "showConfirmDialog" -> 2;
                      case "currentTimeMillis" -> wallTime;
                      case "openFile" -> 3;
                      case "readFromFile" -> {
                        var buffer = (byte[]) args[1];
                        buffer[0] = 1;
                        buffer[1] = 2;
                        buffer[2] = 3;
                        yield 3;
                      }
                      case "writeToFile" -> args[2];
                      default -> null;
                    };
                  });
      var context = new InstructionContext(session, environment);
      int address = session.memory.dataBaseAddress, buffer = address + 32;
      byte[] message = "hello\0".getBytes();
      for (int i = 0; i < message.length; i++) {
        session.memory.setByte(address + i, message[i]);
      }
      for (int i = 0; i < 4; i++) {
        session.memory.setByte(buffer + i, 65 + i);
      }
      var registers = session.registerFile;
      registers.updateRegister(4, address);
      registers.updateRegister(5, buffer);
      registers.updateRegister(6, 8);
      session.coprocessor1.setRegisterToFloat(12, 1.5f);
      if (number == 3 || number == 58) {
        session.coprocessor1.setRegisterPairToDouble(12, 2.5);
      }
      switch (number) {
        case 1, 34, 35 -> registers.updateRegister(4, 42);
        case 36 -> registers.updateRegister(4, -1);
        case 8 -> {
          registers.updateRegister(4, buffer);
          registers.updateRegister(5, 8);
        }
        case 9 -> registers.updateRegister(4, 4);
        case 11 -> registers.updateRegister(4, 'A');
        case 13 -> registers.updateRegister(5, 0);
        case 14, 15 -> {
          registers.updateRegister(4, 3);
          registers.updateRegister(6, 3);
        }
        case 16 -> registers.updateRegister(4, 3);
        case 17 -> registers.updateRegister(4, 7);
        case 31, 33 -> {
          registers.updateRegister(4, 60);
          registers.updateRegister(5, 0);
          registers.updateRegister(6, 0);
          registers.updateRegister(7, 10);
        }
        case 32 -> registers.updateRegister(4, 250);
        case 40 -> {
          registers.updateRegister(4, 1);
          registers.updateRegister(5, 123);
        }
        case 41, 42, 43, 44 -> {
          registers.updateRegister(4, 1);
          registers.updateRegister(5, 10);
        }
        case 55 -> registers.updateRegister(5, 1);
        case 56 -> registers.updateRegister(5, 42);
      }
      session.randomStreams.setSeed(1, 123);
      int heap = session.memory.heapAddress;
      var service = session.syscalls.findByNumber(number);
      if (number == 10 || number == 17) {
        var failure = catchThrowable(() -> service.simulate(null, context));
        assertThat(failure).isInstanceOf(ProcessingException.class);
        assertThat(((ProcessingException) failure).errors()).isNull();
        assertThat(context.getExitCode()).isEqualTo(number == 17 ? 7 : 0);
      } else {
        service.simulate(null, context);
      }
      switch (number) {
        case 1 -> assertThat(calls).containsExactly("printInt[42]");
        case 2 -> assertThat(calls).containsExactly("printFloat[1.5]");
        case 3 -> assertThat(calls).containsExactly("printDouble[2.5]");
        case 4 ->
            assertThat(calls)
                .containsExactly(
                    "printChar[h]", "printChar[e]", "printChar[l]", "printChar[l]", "printChar[o]");
        case 5 -> assertThat(registers.getValue(2)).isEqualTo(7);
        case 6 -> assertThat(session.coprocessor1.getFloatFromRegister(0)).isEqualTo(1.5f);
        case 7 -> assertThat(session.coprocessor1.getDoubleFromRegisterPair(0)).isEqualTo(2.5);
        case 8 -> {
          assertThat(session.memory.getByte(buffer)).isEqualTo('h');
          assertThat(session.memory.getByte(buffer + 2)).isEqualTo('\n');
          assertThat(session.memory.getByte(buffer + 3)).isZero();
        }
        case 9 -> {
          assertThat(registers.getValue(2)).isEqualTo(heap);
          assertThat(session.memory.heapAddress).isEqualTo(heap + 4);
        }
        case 10, 17 -> assertThat(calls).containsExactly("exit[" + (number == 17 ? 7 : 0) + "]");
        case 11 -> assertThat(calls).containsExactly("printChar[A]");
        case 12 -> assertThat(registers.getValue(2)).isEqualTo('Z');
        case 13, 14, 15 -> {
          assertThat(registers.getValue(2)).isEqualTo(3);
          assertThat(calls).hasSize(1);
          if (number == 14) {
            assertThat(session.memory.getByte(buffer + 2)).isEqualTo(3);
          }
        }
        case 16 -> assertThat(calls).containsExactly("closeFile[3]");
        case 30 -> {
          assertThat(calls).containsExactly("currentTimeMillis[]");
          assertThat(registers.getValue(4)).isEqualTo(0x89abcdef);
          assertThat(registers.getValue(5)).isEqualTo(0x01234567);
        }
        case 31, 33 ->
            assertThat(calls).containsExactly("playTone[60, 0, 0, 10, " + (number == 33) + "]");
        case 32 -> assertThat(calls).containsExactly("sleepMillis[250]");
        case 34 -> assertThat(calls).containsExactly("printString[0x0000002a]");
        case 35 ->
            assertThat(calls).containsExactly("printString[00000000000000000000000000101010]");
        case 36 -> assertThat(calls).containsExactly("printString[4294967295]");
        case 40, 41 ->
            assertThat(
                    number == 40 ? session.randomStreams.get(1).nextInt() : registers.getValue(4))
                .isEqualTo(new Random(123).nextInt());
        case 42 -> assertThat(registers.getValue(4)).isEqualTo(new Random(123).nextInt(10));
        case 43 ->
            assertThat(session.coprocessor1.getFloatFromRegister(0))
                .isEqualTo(new Random(123).nextFloat());
        case 44 ->
            assertThat(session.coprocessor1.getDoubleFromRegisterPair(0))
                .isEqualTo(new Random(123).nextDouble());
        case 50 -> assertThat(registers.getValue(4)).isEqualTo(2);
        case 51 -> {
          assertThat(registers.getValue(4)).isEqualTo(42);
          assertThat(registers.getValue(5)).isZero();
        }
        case 52 -> {
          assertThat(session.coprocessor1.getFloatFromRegister(0)).isEqualTo(42f);
          assertThat(registers.getValue(5)).isZero();
        }
        case 53 -> {
          assertThat(session.coprocessor1.getDoubleFromRegisterPair(0)).isEqualTo(42);
          assertThat(registers.getValue(5)).isZero();
        }
        case 54 -> {
          assertThat(session.memory.getByte(buffer)).isEqualTo('4');
          assertThat(session.memory.getByte(buffer + 2)).isEqualTo('\n');
          assertThat(registers.getValue(5)).isZero();
        }
        case 55, 56, 57, 58, 59 ->
            assertThat(calls)
                .hasSize(1)
                .allMatch(call -> call.startsWith("showMessageDialog[hello"));
        case 60 -> assertThat(calls).containsExactly("clearConsole[]");
        default -> throw new AssertionError("Missing syscall vector " + number);
      }
    }
  }

  @Test
  void mutableServiceNumbersAreNotSharedAcrossSessions() {
    var first = new SyscallRegistry();
    var second = new SyscallRegistry();
    first.findByNumber(1).setNumber(101);
    assertThat(second.findByNumber(1).getNumber()).isEqualTo(1);
    assertThat(first.services()).hasSize(40);
  }
}
