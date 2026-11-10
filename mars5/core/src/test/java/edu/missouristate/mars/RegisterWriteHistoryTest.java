package edu.missouristate.mars;

import static edu.missouristate.mars.mips.hardware.Coprocessor0State.EPC;
import static edu.missouristate.mars.mips.hardware.IntegerRegisterFile.HI_REGISTER;
import static edu.missouristate.mars.mips.hardware.IntegerRegisterFile.LO_REGISTER;
import static edu.missouristate.mars.simulator.BackStepper.RegisterWriteBank.CONDITION_FLAGS;
import static edu.missouristate.mars.simulator.BackStepper.RegisterWriteBank.COPROCESSOR_0;
import static edu.missouristate.mars.simulator.BackStepper.RegisterWriteBank.COPROCESSOR_1;
import static edu.missouristate.mars.simulator.BackStepper.RegisterWriteBank.CPU;
import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.simulator.BackStepper.RegisterWrite;
import org.junit.jupiter.api.Test;

class RegisterWriteHistoryTest {
  @Test
  void newestWritingInstructionInEachBankIsRestoredAfterBackstep() {
    try (var machine = TestSessions.create()) {
      var history = machine.backStepper;
      var execution = TestSessions.execution(machine);
      int pc = machine.memory.textBaseAddress;
      var statement = new ProgramStatement(0, pc, machine.instructionSet);
      assertThat(history.previousRegisterWrites()).isEmpty();

      history.beginInstruction(pc, statement);
      execution.updateRegister(8, 42);
      history.endInstruction();

      history.beginInstruction(pc, statement);
      execution.updateRegister(HI_REGISTER, 0);
      execution.updateRegister(LO_REGISTER, 6);
      execution.updateRegister(LO_REGISTER, 6);
      history.endInstruction();

      history.beginInstruction(pc, statement);
      execution.updateCoprocessor0(EPC, 123);
      execution.updateCoprocessor1(0, 1);
      execution.updateCoprocessor1(1, 2);
      execution.setConditionFlag(0);
      history.endInstruction();
      assertThat(history.previousRegisterWrites())
          .containsExactlyInAnyOrder(
              new RegisterWrite(CPU, HI_REGISTER),
              new RegisterWrite(CPU, LO_REGISTER),
              new RegisterWrite(COPROCESSOR_0, EPC),
              new RegisterWrite(COPROCESSOR_1, 0),
              new RegisterWrite(COPROCESSOR_1, 1),
              new RegisterWrite(CONDITION_FLAGS, 0));

      history.backStep();
      assertThat(history.previousRegisterWrites())
          .containsExactlyInAnyOrder(
              new RegisterWrite(CPU, HI_REGISTER), new RegisterWrite(CPU, LO_REGISTER));
      history.backStep();
      assertThat(history.previousRegisterWrites()).containsExactly(new RegisterWrite(CPU, 8));

      execution.updateRegister(9, 7);
      assertThat(history.previousRegisterWrites()).containsExactly(new RegisterWrite(CPU, 8));
      history.setEnabled(false);
      assertThat(history.previousRegisterWrites()).isEmpty();
      history.setEnabled(true);
      history.clear();
      assertThat(history.previousRegisterWrites()).isEmpty();
    }
  }
}
