package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Coprocessor0UndoIsolationTest {
  private edu.missouristate.mars.core.MarsContext machine = TestSessions.create();

  @org.junit.jupiter.api.AfterEach
  void closeMachine() {
    machine.close();
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 12, 13, 14})
  void facadePreservesBacksteppingAndNamedWritesRemainUnrecorded(int number) throws Exception {

    var previousProgram = machine.getProgram();
    var previousSymbols = machine.symbolTable;
    int previousPc = machine.registerFile.getProgramCounter();
    int previousValue = machine.coprocessor0.getValue(number);
    try {
      var program = new MIPSprogram(machine);
      String path = SimulatorSmokeTest.resourcePath("01_helloworld.asm");
      program.readSource(path);
      program.tokenize();
      var filenames = new ArrayList<String>();
      filenames.add(path);
      program.assemble(program.prepareFilesForAssembly(filenames, path, null), true, false);
      var backStepper = program.getBackStepper();
      machine.registerFile.initializeProgramCounter(
          machine.registerFile.getInitialProgramCounter() + 4);

      machine.backStepper.clear();
      var independent = new Coprocessor0State();
      independent.updateRegister(number, 101);
      assertThat(backStepper.empty()).isTrue();
      assertThat(machine.coprocessor0.getValue(number)).isEqualTo(previousValue);
      assertThat(TestSessions.execution(machine).updateCoprocessor0(number, 42))
          .isEqualTo(previousValue);
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(machine.coprocessor0.getValue(number)).isEqualTo(previousValue);
      assertThat(independent.getValue(number)).isEqualTo(101);
      assertThat(backStepper.empty()).isTrue();

      // The original string overload updates the register without recording undo history.
      assertThat(machine.coprocessor0.updateRegister("$" + number, 43)).isEqualTo(previousValue);
      assertThat(machine.coprocessor0.getValue(number)).isEqualTo(43);
      assertThat(backStepper.empty()).isTrue();
    } finally {
      TestSessions.execution(machine).updateCoprocessor0(number, previousValue);
      machine.registerFile.initializeProgramCounter(previousPc);
    }
  }
}
