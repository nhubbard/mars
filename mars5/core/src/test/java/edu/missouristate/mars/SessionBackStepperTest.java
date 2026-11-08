package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.di.MarsComponent;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.mips.instructions.ContextualSimulationCode;
import edu.missouristate.mars.mips.instructions.basic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionBackStepperTest {
  @Test
  void undoRestoresOnlyTheOwningSessionsRegistersMemoryAndPc() throws Exception {
    var first = session(32);
    var second = session(32);
    var firstExecution = execution(first);
    var secondExecution = execution(second);
    int base = first.memory.textBaseAddress;
    int address = first.memory.dataBaseAddress;
    first.registerFile.setValue(9, address);
    second.registerFile.setValue(9, address);
    firstExecution.execute(statement(new InstrAddiu(), 8, 0, 42));
    secondExecution.execute(statement(new InstrAddiu(), 8, 0, 99));
    firstExecution.execute(statement(new InstrSw(), 8, 0, 9));
    secondExecution.execute(statement(new InstrSw(), 8, 0, 9));
    first.backStepper.backStep();
    assertThat(first.memory.getWord(address)).isZero();
    assertThat(first.registerFile.getValue(8)).isEqualTo(42);
    assertThat(first.registerFile.getPC()).isEqualTo(base + 4);
    assertThat(second.memory.getWord(address)).isEqualTo(99);
    assertThat(second.registerFile.getPC()).isEqualTo(base + 8);
    first.backStepper.backStep();
    assertThat(first.registerFile.getValue(8)).isZero();
    assertThat(first.registerFile.getPC()).isEqualTo(base);
    assertThat(first.backStepper.empty()).isTrue();
    assertThat(second.backStepper.empty()).isFalse();
    second.backStepper.backStep();
    second.backStepper.backStep();
    assertThat(second.registerFile.getValue(8)).isZero();
    assertThat(second.memory.getWord(address)).isZero();
  }

  @Test
  void repeatedExecutionOfTheSameStatementUndoesOneIterationAtATime() throws Exception {
    var session = session(32);
    var execution = execution(session);
    int base = session.memory.textBaseAddress;
    var loop =
        custom(
            (statement, context) -> {
              context.updateRegister(8, context.registerFile.getValue(8) + 1);
              context.setProgramCounter(base);
            });
    var statement = statement(loop);
    for (int i = 0; i < 3; i++) execution.execute(statement);
    assertThat(session.registerFile.getValue(8)).isEqualTo(3);
    for (int expected = 2; expected >= 0; expected--) {
      session.backStepper.backStep();
      assertThat(session.registerFile.getValue(8)).isEqualTo(expected);
      assertThat(session.registerFile.getPC()).isEqualTo(base);
      assertThat(session.backStepper.empty()).isEqualTo(expected == 0);
    }
  }

  @Test
  void doublePrecisionAndConditionUpdatesRemainSingleUndoSteps() throws Exception {
    var session = session(32);
    var execution = execution(session);
    long originalBits = 0x7ff8123456789abcL;
    session.coprocessor1.setRegisterPairToDouble(0, 1.25);
    session.coprocessor1.setRegisterPairToDouble(2, 2.75);
    session.coprocessor1.setRegisterPairToLong(4, originalBits);
    execution.execute(statement(new InstrAddD(), 4, 0, 2));
    execution.execute(statement(new InstrCLtD2(), 7, 0, 2));
    assertThat(session.coprocessor1.getConditionFlag(7)).isEqualTo(1);
    session.backStepper.backStep();
    assertThat(session.coprocessor1.getConditionFlag(7)).isZero();
    assertThat(session.coprocessor1.getDoubleFromRegisterPair(4)).isEqualTo(4.0);
    session.backStepper.backStep();
    assertThat(session.coprocessor1.getLongFromRegisterPair(4)).isEqualTo(originalBits);
    assertThat(session.registerFile.getPC()).isEqualTo(session.memory.textBaseAddress);
    assertThat(session.backStepper.empty()).isTrue();
  }

  @Test
  void exceptionReturnAndRuntimeFaultsUndoTheirCompleteHardwareChanges() throws Exception {
    var session = session(32);
    var execution = execution(session);
    int base = session.memory.textBaseAddress;
    session.coprocessor0.updateRegister(Coprocessor0State.EPC, base + 64);
    session.coprocessor0.updateRegister(Coprocessor0State.STATUS, 0xff13);
    execution.execute(statement(new InstrEret()));
    assertThat(session.registerFile.getPC()).isEqualTo(base + 64);
    session.backStepper.backStep();
    assertThat(session.registerFile.getPC()).isEqualTo(base);
    assertThat(session.coprocessor0.getValue(Coprocessor0State.STATUS)).isEqualTo(0xff13);
    session.coprocessor0.resetRegisters();
    session.registerFile.setValue(8, Integer.MAX_VALUE);
    session.registerFile.setValue(9, 1);
    assertThatThrownBy(() -> execution.execute(statement(new InstrAdd(), 10, 8, 9)))
        .isInstanceOf(ProcessingException.class);
    assertThat(session.coprocessor0.getValue(Coprocessor0State.CAUSE)).isNotZero();
    session.backStepper.backStep();
    assertThat(session.coprocessor0.getValue(Coprocessor0State.CAUSE)).isZero();
    assertThat(session.coprocessor0.getValue(Coprocessor0State.EPC)).isZero();
    assertThat(session.coprocessor0.getValue(Coprocessor0State.STATUS)).isEqualTo(0xff11);
    assertThat(session.registerFile.getPC()).isEqualTo(base);
    execution.execute(statement(new InstrNop()));
    session.backStepper.backStep();
    assertThat(session.backStepper.empty()).isTrue();
  }

  @Test
  void invalidInstructionsProduceUndoableExceptionState() throws Exception {
    var session = session(16);
    var execution = execution(session);
    int base = session.memory.textBaseAddress;
    var invalid = new ProgramStatement(null, "invalid", null, null, null, base, 1);
    assertThatThrownBy(() -> execution.execute(invalid)).isInstanceOf(ProcessingException.class);
    assertThat(session.coprocessor0.getValue(Coprocessor0State.CAUSE)).isEqualTo(10 << 2);
    session.backStepper.backStep();
    assertThat(session.coprocessor0.getValue(Coprocessor0State.CAUSE)).isZero();
    assertThat(session.registerFile.getPC()).isEqualTo(base);
    assertThat(session.backStepper.empty()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"word", "half", "byte", "raw"})
  void memoryWritesRestoreThePreviousValue(String operation) throws Exception {
    var session = session(16);
    var execution = execution(session);
    int address = session.memory.dataBaseAddress;
    session.memory.setWord(address, 0x12345678);
    execution.execute(
        statement(
            custom(
                (statement, context) -> {
                  try {
                    switch (operation) {
                      case "word" -> context.setMemoryWord(address, 0x76543210);
                      case "half" -> context.setMemoryHalf(address, 0xabcd);
                      case "byte" -> context.setMemoryByte(address, 0xff);
                      case "raw" -> context.setMemoryRawWord(address, 0x76543210);
                      default -> throw new IllegalArgumentException(operation);
                    }
                  } catch (edu.missouristate.mars.mips.hardware.AddressErrorException error) {
                    throw new ProcessingException(context, statement, error);
                  }
                })));
    assertThat(session.memory.getWord(address)).isNotEqualTo(0x12345678);
    session.backStepper.backStep();
    assertThat(session.memory.getWord(address)).isEqualTo(0x12345678);
    assertThat(session.backStepper.empty()).isTrue();
  }

  @Test
  void allocationAndFloatingPointHelperWritesUndoWithTheirInstruction() throws Exception {
    var session = session(32);
    var execution = execution(session);
    int base = session.memory.textBaseAddress;
    session.registerFile.setValue(2, 9);
    session.registerFile.setValue(4, 5);
    execution.execute(statement(new InstrSyscall()));
    assertThat(session.memory.heapAddress).isEqualTo(session.memory.heapBaseAddress + 8);
    session.backStepper.backStep();
    assertThat(session.memory.heapAddress).isEqualTo(session.memory.heapBaseAddress);
    assertThat(session.registerFile.getValue(2)).isEqualTo(9);
    assertThat(session.registerFile.getPC()).isEqualTo(base);
    session.coprocessor1.setRegisterPairToLong(0, 0x0123456789abcdefL);
    execution.execute(
        statement(
            custom(
                (statement, context) -> {
                  try {
                    context.setRegisterPairToDouble(0, 3.5);
                  } catch (
                      edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException error) {
                    throw new AssertionError(error);
                  }
                })));
    assertThat(session.coprocessor1.getDoubleFromRegisterPair(0)).isEqualTo(3.5);
    session.backStepper.backStep();
    assertThat(session.coprocessor1.getLongFromRegisterPair(0)).isEqualTo(0x0123456789abcdefL);
    assertThat(session.backStepper.empty()).isTrue();
  }

  @Test
  void undoingADelaySlotRestoresThePendingBranchAndAllowsReexecution() throws Exception {
    var session = session(32);
    var execution = execution(session);
    int base = session.memory.textBaseAddress;
    session.settings.setDelayedBranchingEnabled(true);
    var initialBranch = session.delayedBranch.snapshot();
    execution.execute(statement(new InstrBeq(), 0, 0, 3));
    assertThat(session.backStepper.inDelaySlot()).isFalse();
    var pendingBranch = session.delayedBranch.snapshot();
    var delaySlot = statement(new InstrAddiu(), 8, 0, 42);
    execution.execute(delaySlot);
    assertThat(session.registerFile.getPC()).isEqualTo(base + 16);
    assertThat(session.backStepper.inDelaySlot()).isTrue();
    session.backStepper.backStep();
    assertThat(session.registerFile.getPC()).isEqualTo(base + 4);
    assertThat(session.registerFile.getValue(8)).isZero();
    assertThat(session.delayedBranch.snapshot()).isEqualTo(pendingBranch);
    execution.execute(delaySlot);
    assertThat(session.registerFile.getPC()).isEqualTo(base + 16);
    session.backStepper.backStep();
    session.backStepper.backStep();
    assertThat(session.registerFile.getPC()).isEqualTo(base);
    assertThat(session.delayedBranch.snapshot()).isEqualTo(initialBranch);
    assertThat(session.backStepper.empty()).isTrue();
  }

  @Test
  void boundedHistoryEvictsWholeInstructionsRatherThanPartialWrites() throws Exception {
    var session = session(3);
    var execution = execution(session);
    for (int value = 1; value <= 2; value++) {
      int result = value;
      execution.execute(
          statement(
              custom(
                  (statement, context) -> {
                    context.updateRegister(8, result);
                    context.updateRegister(9, result);
                  })));
    }
    session.backStepper.backStep();
    assertThat(session.registerFile.getValue(8)).isEqualTo(1);
    assertThat(session.registerFile.getValue(9)).isEqualTo(1);
    assertThat(session.backStepper.empty()).isTrue();
  }

  @Test
  void anOversizedInstructionLeavesNoPartialUndoAndLaterStepsRemainUsable() throws Exception {
    var session = session(2);
    var execution = execution(session);
    execution.execute(
        statement(
            custom(
                (statement, context) -> {
                  context.updateRegister(8, 1);
                  context.updateRegister(9, 2);
                  context.updateRegister(10, 3);
                })));
    assertThat(session.backStepper.empty()).isTrue();
    execution.execute(statement(new InstrAddiu(), 8, 0, 99));
    session.backStepper.backStep();
    assertThat(session.registerFile.getValue(8)).isEqualTo(1);
    assertThat(session.registerFile.getValue(9)).isEqualTo(2);
    assertThat(session.registerFile.getValue(10)).isEqualTo(3);
    assertThat(session.backStepper.empty()).isTrue();
  }

  @Test
  void disablingAndClearingHistoryLeaveExecutionUsable() throws Exception {
    var component = component(16);
    var session = component.context();
    var execution = execution(session);
    session.backStepper.setEnabled(false);
    execution.execute(statement(new InstrAddiu(), 8, 0, 42));
    assertThat(session.backStepper.empty()).isTrue();
    session.backStepper.setEnabled(true);
    execution.execute(statement(new InstrNop()));
    assertThat(session.backStepper.empty()).isFalse();
    component.memory().clear();
    assertThat(session.backStepper.empty()).isTrue();
    execution.execute(statement(new InstrNop()));
    session.setMemoryConfiguration("CompactDataAtZero");
    assertThat(session.backStepper.empty()).isTrue();
    assertThat(session.registerFile.getPC()).isEqualTo(0x3000);
  }

  @Test
  void anUndoFailureRaisesAnErrorWithoutTerminatingTheProcessOrDisablingHistory() throws Exception {
    var session = session(16);
    session.backStepper.addMemoryRestoreByte(3, 42);
    assertThatThrownBy(session.backStepper::backStep)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("restore memory");
    assertThat(session.backStepper.enabled()).isTrue();
    var execution = execution(session);
    execution.execute(statement(new InstrNop()));
    session.backStepper.backStep();
    assertThat(session.backStepper.empty()).isTrue();
  }

  private static MarsComponent component(int capacity) {
    var settings = new Settings();
    settings
        .getSimulationSettings()
        .setMemoryConfiguration("Default")
        .setDelayedBranchingEnabled(false);
    return DaggerMarsComponent.builder()
        .coreModule(new CoreModule(settings, capacity))
        .guiProxy(NO_GUI)
        .build();
  }

  private static MarsContext session(int capacity) {
    return component(capacity).context();
  }

  private static InstructionContext execution(MarsContext session) {
    return new InstructionContext(session, session.syscallEnvironment);
  }

  private static ProgramStatement statement(BasicInstruction instruction, int... operands) {
    var statement = new ProgramStatement(null, "test", null, null, instruction, 0x400000, 1);
    System.arraycopy(operands, 0, statement.getOperands(), 0, operands.length);
    return statement;
  }

  private static BasicInstruction custom(ContextualSimulationCode code) {
    return new BasicInstruction(
        "test", BasicInstructionFormat.R_FORMAT, "00000000000000000000000000000000", code);
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
}
