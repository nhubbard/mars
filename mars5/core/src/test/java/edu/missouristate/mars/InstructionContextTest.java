package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.mips.instructions.basic.*;
import edu.missouristate.mars.mips.instructions.syscalls.AbstractSyscall;
import edu.missouristate.mars.simulator.Exceptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstructionContextTest {
  private static final int BASE = 0x00400000;

  @Test
  void interleavedProgramsReuseInstructionsWithoutSharingRegisterState() throws Exception {
    var first = context(false, new RecordingEnvironment());
    var second = context(false, new RecordingEnvironment());
    first.registerFile.setValue(4, 2);
    second.registerFile.setValue(4, 5);
    var code =
        List.of(
            statement(new InstrAddu(), 8, 4, 0),
            statement(new InstrAddiu(), 9, 8, 3),
            statement(new InstrAddiu(), 8, 8, 1),
            statement(new InstrBne(), 8, 9, -2),
            statement(new InstrMult(), 8, 9),
            statement(new InstrMflo(), 10),
            statement(new InstrMtc1(), 10, 0),
            statement(new InstrCvtSW(), 2, 0));
    first.registerFile.initializeProgramCounter(BASE);
    second.registerFile.initializeProgramCounter(BASE);
    for (int step = 0; step < 30; step++) {
      stepProgram(first, code);
      stepProgram(second, code);
    }
    assertThat(first.registerFile.getProgramCounter()).isEqualTo(BASE + code.size() * 4);
    assertThat(second.registerFile.getProgramCounter()).isEqualTo(BASE + code.size() * 4);
    assertThat(first.registerFile.getValue(8)).isEqualTo(5);
    assertThat(second.registerFile.getValue(8)).isEqualTo(8);
    assertThat(first.registerFile.getValue(10)).isEqualTo(25);
    assertThat(second.registerFile.getValue(10)).isEqualTo(64);
    assertThat(first.coprocessor1.getFloatFromRegister(2)).isEqualTo(25.0f);
    assertThat(second.coprocessor1.getFloatFromRegister(2)).isEqualTo(64.0f);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void jumpAndLinkUseTheContextsDelaySetting(boolean delayed) throws Exception {
    var context = context(delayed, new RecordingEnvironment());
    context.registerFile.initializeProgramCounter(BASE + 4);
    context.registerFile.setValue(8, BASE + 0x100);
    execute(new InstrJalr2(), context, 8);
    assertThat(context.registerFile.getValue(31)).isEqualTo(BASE + (delayed ? 8 : 4));
    if (delayed) {
      assertThat(context.registerFile.getProgramCounter()).isEqualTo(BASE + 4);
      context.completeInstruction();
      assertThat(context.delayedBranch.isTriggered()).isTrue();
      context.registerFile.incrementPC();
      execute(new InstrAddiu(), context, 9, 0, 42);
      context.completeInstruction();
      assertThat(context.registerFile.getValue(9)).isEqualTo(42);
      assertThat(context.delayedBranch.isTriggered()).isFalse();
    }
    assertThat(context.registerFile.getProgramCounter()).isEqualTo(BASE + 0x100);
  }

  @Test
  void pendingBranchesBelongToTheirOwnSessionsAndKeepTheOriginalTarget() throws Exception {
    var first = context(true, new RecordingEnvironment());
    var second = context(true, new RecordingEnvironment());
    first.registerFile.initializeProgramCounter(BASE + 4);
    second.registerFile.initializeProgramCounter(BASE + 4);
    execute(new InstrBeq(), first, 0, 0, 10);
    execute(new InstrBeq(), second, 0, 0, 20);
    first.completeInstruction();
    second.completeInstruction();
    // A branch in the delay slot extends the slot without replacing the original target.
    execute(new InstrBeq(), first, 0, 0, 30);
    first.completeInstruction();
    assertThat(first.registerFile.getProgramCounter()).isEqualTo(BASE + 4);
    second.completeInstruction();
    assertThat(second.registerFile.getProgramCounter()).isEqualTo(BASE + 84);
    first.completeInstruction();
    assertThat(first.registerFile.getProgramCounter()).isEqualTo(BASE + 44);
  }

  @Test
  void floatingPointComparisonBranchAndExceptionReturnUseOnlyTheirContext() throws Exception {
    var first = context(false, new RecordingEnvironment());
    var second = context(false, new RecordingEnvironment());
    first.registerFile.initializeProgramCounter(BASE + 4);
    first.coprocessor1.setRegisterToFloat(0, 1.5f);
    first.coprocessor1.setRegisterToFloat(2, 2.5f);
    execute(new InstrAddS(), first, 4, 0, 2);
    execute(new InstrCvtDS(), first, 6, 4);
    execute(new InstrCLtS2(), first, 7, 0, 2);
    execute(new InstrBc1t2(), first, 7, 3);
    assertThat(first.coprocessor1.getFloatFromRegister(4)).isEqualTo(4.0f);
    assertThat(first.coprocessor1.getDoubleFromRegisterPair(6)).isEqualTo(4.0);
    assertThat(first.registerFile.getProgramCounter()).isEqualTo(BASE + 16);
    assertThat(second.coprocessor1.getValue(4)).isZero();
    assertThat(second.coprocessor1.getConditionFlag(7)).isZero();
    first.coprocessor0.updateRegister(Coprocessor0State.EPC, BASE + 64);
    first.coprocessor0.updateRegister(Coprocessor0State.STATUS, 0xff13);
    execute(new InstrEret(), first);
    assertThat(first.registerFile.getProgramCounter()).isEqualTo(BASE + 64);
    assertThat(first.coprocessor0.getValue(Coprocessor0State.STATUS)).isEqualTo(0xff11);
    execute(new InstrMfc0(), first, 8, Coprocessor0State.EPC);
    execute(new InstrMtc0(), first, 8, Coprocessor0State.VADDR);
    assertThat(first.coprocessor0.getValue(Coprocessor0State.VADDR)).isEqualTo(BASE + 64);
    assertThat(second.coprocessor0.getValue(Coprocessor0State.VADDR)).isZero();
  }

  @Test
  void overflowAndAddressFaultsUpdateOnlyTheSuppliedExceptionRegisters() {
    var context = context(false, new RecordingEnvironment());
    context.registerFile.initializeProgramCounter(BASE + 0x104);
    context.registerFile.setValue(8, Integer.MAX_VALUE);
    context.registerFile.setValue(9, 1);
    assertThatThrownBy(() -> execute(new InstrAdd(), context, 10, 8, 9))
        .isInstanceOfSatisfying(
            ProcessingException.class,
            error ->
                assertThat(error.errors().generateErrorReport())
                    .contains("0x00400100", "arithmetic overflow"));
    assertThat(context.coprocessor0.getValue(Coprocessor0State.CAUSE))
        .isEqualTo(Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION << 2);
    assertThat(context.coprocessor0.getValue(Coprocessor0State.EPC)).isEqualTo(BASE + 0x100);
    assertThat(context.coprocessor0.getValue(Coprocessor0State.STATUS) & 2).isEqualTo(2);
    assertThatThrownBy(() -> execute(new InstrLw(), context, 10, 3, 0))
        .isInstanceOf(ProcessingException.class);
    assertThat(context.coprocessor0.getValue(Coprocessor0State.VADDR)).isEqualTo(3);
  }

  @Test
  void sharedSyscallImplementationsUseTheInvocationsRegistersAndEnvironment() throws Exception {
    var firstIo = new RecordingEnvironment();
    var secondIo = new RecordingEnvironment();
    var first = context(false, firstIo);
    var second = context(false, secondIo);
    var syscall = new InstrSyscall();
    first.registerFile.setValue(2, 1);
    first.registerFile.setValue(4, 42);
    second.registerFile.setValue(2, 1);
    second.registerFile.setValue(4, 99);
    execute(syscall, first);
    execute(syscall, second);
    assertThat(firstIo.printed).containsExactly(42);
    assertThat(secondIo.printed).containsExactly(99);
    firstIo.input = 123;
    secondIo.input = 456;
    first.registerFile.setValue(2, 5);
    second.registerFile.setValue(2, 5);
    execute(syscall, first);
    execute(syscall, second);
    assertThat(first.registerFile.getValue(2)).isEqualTo(123);
    assertThat(second.registerFile.getValue(2)).isEqualTo(456);
    first.registerFile.setValue(2, 17);
    first.registerFile.setValue(4, 7);
    assertThatThrownBy(() -> execute(syscall, first))
        .isInstanceOfSatisfying(
            ProcessingException.class, error -> assertThat(error.errors()).isNull());
    assertThat(first.getExitCode()).isEqualTo(7);
    assertThat(firstIo.exitCode).isEqualTo(7);
    assertThat(second.getExitCode()).isZero();
  }

  @Test
  void customInstructionAndSyscallReceiveExecutionContexts() throws Exception {
    var calls = new AtomicInteger();
    var instruction =
        new BasicInstruction(
            "custom",
            BasicInstructionFormat.R_FORMAT,
            "00000000000000000000000000000000",
            (_, _) -> calls.incrementAndGet());
    var syscall =
        new AbstractSyscall(123, "Legacy") {
          @Override
          public void simulate(ProgramStatement statement, @NotNull InstructionContext execution) {
            calls.incrementAndGet();
          }
        };
    var context = context(false, new RecordingEnvironment());
    var statement = statement(instruction);
    instruction.getSimulationCode().simulate(statement, context);
    syscall.simulate(statement, context);
    assertThat(calls.get()).isEqualTo(2);
  }

  private static InstructionContext context(boolean delayed, RecordingEnvironment environment) {
    var settings = new Settings();
    settings.getSimulationSettings().setDelayedBranchingEnabled(delayed);
    MarsContext session =
        DaggerMarsComponent.builder()
            .coreModule(new CoreModule(settings))
            .guiProxy(TestSessions.NO_GUI)
            .build()
            .context();
    return new InstructionContext(session, environment);
  }

  private static ProgramStatement statement(BasicInstruction instruction, int... operands) {
    var statement = new ProgramStatement(null, "test", null, null, instruction, BASE, 1);
    System.arraycopy(operands, 0, statement.getOperands(), 0, operands.length);
    return statement;
  }

  private static void execute(
      BasicInstruction instruction, InstructionContext context, int... operands)
      throws ProcessingException {
    instruction.getSimulationCode().simulate(statement(instruction, operands), context);
  }

  private static void stepProgram(InstructionContext context, List<ProgramStatement> code)
      throws ProcessingException {
    int index = (context.registerFile.getProgramCounter() - BASE) / 4;
    if (index == code.size()) {
      return;
    }
    assertThat(index).isBetween(0, code.size() - 1);
    var statement = code.get(index);
    context.registerFile.incrementPC();
    ((BasicInstruction) statement.getInstruction())
        .getSimulationCode()
        .simulate(statement, context);
    context.completeInstruction();
  }

  private static class RecordingEnvironment extends TestEnvironment {
    final List<Integer> printed = new ArrayList<>();
    int input;
    int exitCode;

    @Override
    public void printInt(int value) {
      printed.add(value);
    }

    @Override
    public int readInt() {
      return input;
    }

    @Override
    public void exit(int code) {
      exitCode = code;
    }
  }
}
