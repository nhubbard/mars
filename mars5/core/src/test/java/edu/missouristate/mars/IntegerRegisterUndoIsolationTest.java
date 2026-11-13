package edu.missouristate.mars;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.instructions.basic.InstrAddi;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class IntegerRegisterUndoIsolationTest {
  private final MarsContext machine = TestSessions.create();

  @org.junit.jupiter.api.AfterEach
  void closeMachine() {
    machine.close();
  }

  @Test
  void facadePreservesBacksteppingWithoutRecordingIndependentInstanceWrites() throws Exception {

    int[] previousValues = new int[35];
    for (int i = 0; i < 35; i++) {
      previousValues[i] =
          i == 32 ? machine.registerFile.getProgramCounter() : machine.registerFile.getValue(i);
    }
    try {
      var program = new MIPSprogram(machine);
      String path = SimulatorSmokeTest.resourcePath("01_helloworld.asm");
      program.readSource(path);
      program.tokenize();
      var filenames = new ArrayList<String>();
      filenames.add(path);
      program.assemble(program.prepareFilesForAssembly(filenames, path, null), true, false);
      var backStepper = requireNonNull(program.getBackStepper());
      int pc = machine.registerFile.getInitialProgramCounter();
      machine.registerFile.initializeProgramCounter(pc + 4);
      int previousT0 = machine.registerFile.getValue(8);
      int previousHi = machine.registerFile.getValue(33);
      int previousLo = machine.registerFile.getValue(34);

      machine.backStepper.clear();
      var independent = new IntegerRegisterFile();
      independent.setValue(8, 99);
      independent.setValue(33, 100);
      independent.setValue(32, 101);
      assertThat(backStepper.empty()).isTrue();
      assertThat(machine.registerFile.getValue(8)).isEqualTo(previousT0);
      assertThat(TestSessions.execution(machine).updateRegister(8, 42)).isEqualTo(previousT0);
      TestSessions.execution(machine).updateRegister(33, 43);
      TestSessions.execution(machine).updateRegister(34, 44);
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(machine.registerFile.getValue(8)).isEqualTo(previousT0);
      assertThat(machine.registerFile.getValue(33)).isEqualTo(previousHi);
      assertThat(machine.registerFile.getValue(34)).isEqualTo(previousLo);
      assertThat(independent.getValue(8)).isEqualTo(99);

      // The explicit compatibility-session invocation must keep recording undo history.
      machine.registerFile.initializeProgramCounter(pc + 4);
      var add = new InstrAddi();
      var statement = new ProgramStatement(null, "addi $t0,$zero,42", null, null, add, pc, 1);
      System.arraycopy(new int[] {8, 0, 42}, 0, requireNonNull(statement.getOperands()), 0, 3);
      add.getSimulationCode()
          .simulate(
              statement,
              new edu.missouristate.mars.core.InstructionContext(
                  machine, machine.syscallEnvironment));
      assertThat(machine.registerFile.getValue(8)).isEqualTo(42);
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(machine.registerFile.getValue(8)).isEqualTo(previousT0);

      machine.registerFile.initializeProgramCounter(pc + 4);
      assertThat(TestSessions.execution(machine).setProgramCounter(pc + 8)).isEqualTo(pc + 4);
      backStepper.backStep();
      // The legacy back-stepper restores the address before the automatic PC increment.
      assertThat(machine.registerFile.getProgramCounter()).isEqualTo(pc);
      TestSessions.execution(machine).updateRegister(0, 1000);
      assertThat(machine.registerFile.getValue(0)).isZero();
    } finally {
      for (int i = 1; i < 35; i++) {
        if (i == 32) {
          machine.registerFile.initializeProgramCounter(previousValues[i]);
        } else {
          TestSessions.execution(machine).updateRegister(i, previousValues[i]);
        }
      }
    }
  }
}
