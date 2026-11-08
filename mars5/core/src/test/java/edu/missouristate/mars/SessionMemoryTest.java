package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.MemoryAccessException;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.di.MarsComponent;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.basic.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionMemoryTest {
  @Test
  void memoryAndDecoderCanStartWithoutGlobalSimulationObjects() throws Exception {

    var session = component("Default").context();
    session.settings.setSelfModifyingCodeEnabled(true);
    session.memory.setWord(session.memory.textBaseAddress, 0x2408002a);
    var statement = session.memory.getStatement(session.memory.textBaseAddress);
    assertThat(statement.getInstruction().getName()).isEqualTo("addiu");
    assertThat(statement.getPrintableBasicAssemblyStatement()).contains("addiu");
    var context = new InstructionContext(session, new RecordingEnvironment());
    context.execute(statement);
    assertThat(session.registerFile.getValue(8)).isEqualTo(42);
    session.backStepper.backStep();
    assertThat(session.registerFile.getValue(8)).isZero();
    assertThat(session.registerFile.getPC()).isEqualTo(session.memory.textBaseAddress);
  }

  @Test
  void interleavedProgramsFetchAndStoreInTheirOwnMemoryAndAllocateTheirOwnHeap() throws Exception {
    var first = component("Default").context();
    var second = component("Default").context();
    var firstContext = new InstructionContext(first, new RecordingEnvironment());
    var secondContext = new InstructionContext(second, new RecordingEnvironment());
    installProgram(first, 42);
    installProgram(second, 99);
    boolean firstDone = false;
    boolean secondDone = false;
    for (int i = 0; i < 20 && !(firstDone && secondDone); i++) {
      if (!firstDone) {
        firstDone = step(firstContext);
      }
      if (!secondDone) {
        secondDone = step(secondContext);
      }
    }
    assertThat(firstDone).isTrue();
    assertThat(secondDone).isTrue();
    assertThat(first.memory.getWord(0x10010000)).isEqualTo(42);
    assertThat(second.memory.getWord(0x10010000)).isEqualTo(99);
    assertThat(first.registerFile.getValue(10)).isEqualTo(42);
    assertThat(second.registerFile.getValue(10)).isEqualTo(99);
    assertThat(first.registerFile.getValue(16)).isEqualTo(first.memory.heapBaseAddress);
    assertThat(second.registerFile.getValue(16)).isEqualTo(second.memory.heapBaseAddress);
    assertThat(first.memory.heapAddress).isEqualTo(first.memory.heapBaseAddress + 8);
    assertThat(second.memory.heapAddress).isEqualTo(second.memory.heapBaseAddress + 8);
    first.memory.clear();
    assertThat(second.memory.getWord(0x10010000)).isEqualTo(99);
    assertThat(second.memory.getStatement(second.memory.textBaseAddress)).isNotNull();
  }

  @Test
  void floatingPointLoadAndStoreUseTheInvocationsMemory() throws Exception {
    var first = new InstructionContext(component("Default").context(), new RecordingEnvironment());
    var second = new InstructionContext(component("Default").context(), new RecordingEnvironment());
    int address = first.memory().dataBaseAddress;
    first.registerFile.setValue(8, address);
    second.registerFile.setValue(8, address);
    first.coprocessor1.setRegisterToFloat(0, 1.5f);
    second.coprocessor1.setRegisterToFloat(0, 2.5f);
    execute(new InstrSwc1(), first, 0, 0, 8);
    execute(new InstrSwc1(), second, 0, 0, 8);
    execute(new InstrLwc1(), first, 2, 0, 8);
    execute(new InstrLwc1(), second, 2, 0, 8);
    assertThat(first.coprocessor1.getFloatFromRegister(2)).isEqualTo(1.5f);
    assertThat(second.coprocessor1.getFloatFromRegister(2)).isEqualTo(2.5f);
    first.coprocessor1.setRegisterPairToDouble(4, 3.5);
    second.coprocessor1.setRegisterPairToDouble(4, 4.5);
    execute(new InstrSdc1(), first, 4, 8, 8);
    execute(new InstrSdc1(), second, 4, 8, 8);
    execute(new InstrLdc1(), first, 6, 8, 8);
    execute(new InstrLdc1(), second, 6, 8, 8);
    assertThat(first.coprocessor1.getDoubleFromRegisterPair(6)).isEqualTo(3.5);
    assertThat(second.coprocessor1.getDoubleFromRegisterPair(6)).isEqualTo(4.5);
  }

  @Test
  void stringAndInputSyscallsUseTheOwnersMemoryAndEnvironment() throws Exception {
    var firstIo = new RecordingEnvironment();
    var secondIo = new RecordingEnvironment();
    var first = new InstructionContext(component("Default").context(), firstIo);
    var second = new InstructionContext(component("Default").context(), secondIo);
    int address = first.memory().dataBaseAddress;
    writeString(first, address, "first");
    writeString(second, address, "second");
    first.registerFile.setValue(4, address);
    second.registerFile.setValue(4, address);
    first.registerFile.setValue(2, 4);
    second.registerFile.setValue(2, 4);
    execute(new InstrSyscall(), first);
    execute(new InstrSyscall(), second);
    assertThat(String.join("", firstIo.printed)).isEqualTo("first");
    assertThat(String.join("", secondIo.printed)).isEqualTo("second");
    firstIo.input = "alpha";
    secondIo.input = "beta";
    first.registerFile.setValue(2, 8);
    second.registerFile.setValue(2, 8);
    first.registerFile.setValue(5, 16);
    second.registerFile.setValue(5, 16);
    execute(new InstrSyscall(), first);
    execute(new InstrSyscall(), second);
    assertThat(first.memory().getByte(address)).isEqualTo('a');
    assertThat(second.memory().getByte(address)).isEqualTo('b');
  }

  @Test
  void switchingSessionConfigurationResetsItsMemoryAndPointersOnly() throws Exception {
    var first = component("Default").context();
    var second = component("Default").context();
    first.memory.setWord(first.memory.dataBaseAddress, 42);
    second.memory.setWord(second.memory.dataBaseAddress, 99);
    second.memory.allocateBytesFromHeap(8);
    int legacyText = second.memory.textBaseAddress;
    first.setMemoryConfiguration("CompactTextAtZero");
    assertThat(first.memory.textBaseAddress).isZero();
    assertThat(first.registerFile.getPC()).isZero();
    assertThat(first.registerFile.getValue(28)).isEqualTo(0x1800);
    assertThat(first.registerFile.getValue(29)).isEqualTo(0x3ffc);
    assertThat(first.memory.getWord(first.memory.dataBaseAddress)).isZero();
    assertThat(first.settings.getMemoryConfiguration()).isEqualTo("CompactTextAtZero");
    assertThat(second.memory.textBaseAddress).isEqualTo(0x400000);
    assertThat(second.memory.getWord(second.memory.dataBaseAddress)).isEqualTo(99);
    assertThat(second.memory.heapAddress).isEqualTo(second.memory.heapBaseAddress + 8);
    assertThat(second.memory.textBaseAddress).isEqualTo(legacyText);
  }

  @Test
  void publicMemoryApiUsesTheSameStateAndTranslatesAddressErrors() throws Exception {
    var component = component("Default");
    var api = component.memory();
    var memory = component.context().memory;
    int address = memory.dataBaseAddress;
    api.setWord(address, 0x1234abcd);
    assertThat(memory.getWord(address)).isEqualTo(0x1234abcd);
    assertThat(api.getHalfWord(address)).isEqualTo(0xabcd);
    assertThat(api.getByte(address)).isEqualTo(0xcd);
    assertThatExceptionOfType(MemoryAccessException.class)
        .isThrownBy(() -> api.getWord(address + 1))
        .satisfies(
            error -> {
              assertThat(error.getAddress()).isEqualTo(address + 1);
              assertThat(error.getCause()).isInstanceOf(AddressErrorException.class);
            });
    api.clear();
    assertThat(memory.getWord(address)).isZero();
  }

  private static MarsComponent component(String layout) {
    var settings = new Settings();
    settings.getSimulationSettings().setMemoryConfiguration(layout);
    return DaggerMarsComponent.builder()
        .coreModule(new CoreModule(settings))
        .guiProxy(NO_GUI)
        .build();
  }

  private static void installProgram(MarsContext session, int value) throws Exception {
    int[] words = {
      0x3c091001,
      0x24080000 | value,
      0xad280000,
      0x8d2a0000,
      0x24020009,
      0x24040005,
      0x0000000c,
      0x00408021,
      0x2402000a,
      0x0000000c
    };
    int base = session.memory.textBaseAddress;
    for (int i = 0; i < words.length; i++) {
      int address = base + i * 4;
      session.memory.setStatement(
          address, new ProgramStatement(words[i], address, session.instructionSet));
    }
    session.registerFile.initializeProgramCounter(base);
  }

  private static boolean step(InstructionContext context) throws Exception {
    var statement = context.memory().getStatement(context.registerFile.getPC());
    assertThat(statement).isNotNull();
    try {
      context.execute(statement);
      return false;
    } catch (ProcessingException error) {
      if (error.errors() != null) {
        throw error;
      }
      return true;
    }
  }

  private static void execute(
      BasicInstruction instruction, InstructionContext context, int... operands) throws Exception {
    var statement =
        new ProgramStatement(
            null, "test", null, null, instruction, context.memory().textBaseAddress, 1);
    System.arraycopy(operands, 0, statement.getOperands(), 0, operands.length);
    instruction.getSimulationCode().simulate(statement, context);
  }

  private static void writeString(InstructionContext context, int address, String value)
      throws Exception {
    for (int i = 0; i < value.length(); i++) {
      context.memory().setByte(address + i, value.charAt(i));
    }
    context.memory().setByte(address + value.length(), 0);
  }

  private static final MarsGuiProxy NO_GUI =
      new MarsGuiProxy() {
        public boolean isGuiMode() {
          return false;
        }

        public void postMessage(String message) {}

        public void postRunMessage(String message) {}

        public String readInput(int maxLength) {
          return "";
        }

        public String readInputWithPrompt(String prompt) {
          return "";
        }

        public void clearRunConsole() {}
      };

  private static class RecordingEnvironment extends TestEnvironment {
    final List<String> printed = new ArrayList<>();
    String input = "";

    @Override
    public void printString(String value) {
      printed.add(value);
    }

    @Override
    public void printChar(char value) {
      printed.add(String.valueOf(value));
    }

    @Override
    public String readString(int maxLength) {
      return input;
    }
  }
}
