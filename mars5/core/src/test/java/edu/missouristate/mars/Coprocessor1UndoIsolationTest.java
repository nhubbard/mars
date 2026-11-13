package edu.missouristate.mars;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.instructions.basic.InstrAddS;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Coprocessor1UndoIsolationTest {
  private final MarsContext machine = TestSessions.create();

  @org.junit.jupiter.api.AfterEach
  void closeMachine() {
    machine.close();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void facadePreservesRegisterAndConditionFlagBackstepping(int flag) throws Exception {

    int previousPc = machine.registerFile.getProgramCounter();
    int previousFlags = machine.coprocessor1.getConditionFlags();
    int[] previousValues = new int[32];
    for (int i = 0; i < 32; i++) {
      previousValues[i] = machine.coprocessor1.getValue(i);
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
      int pc = machine.registerFile.getInitialProgramCounter() + 4;
      machine.registerFile.initializeProgramCounter(pc);
      machine.backStepper.clear();
      var independent = new Coprocessor1State();
      independent.updateRegister(12, 101);
      independent.setConditionFlag(flag);
      assertThat(backStepper.empty()).isTrue();
      assertThat(TestSessions.execution(machine).updateCoprocessor1(12, 42))
          .isEqualTo(previousValues[12]);
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(machine.coprocessor1.getValue(12)).isEqualTo(previousValues[12]);
      assertThat(independent.getValue(12)).isEqualTo(101);
      assertThat(independent.getConditionFlag(flag)).isEqualTo(1);

      machine.coprocessor1.setRegisterToFloat(12, 1.5f);
      machine.registerFile.initializeProgramCounter(pc);
      var add = new InstrAddS();
      var statement =
          new ProgramStatement(null, "add.s $f14,$f12,$f12", null, null, add, pc - 4, 1);
      System.arraycopy(new int[] {14, 12, 12}, 0, requireNonNull(statement.getOperands()), 0, 3);
      add.getSimulationCode()
          .simulate(
              statement,
              new edu.missouristate.mars.core.InstructionContext(
                  machine, machine.syscallEnvironment));
      assertThat(machine.coprocessor1.getFloatFromRegister(14)).isEqualTo(3.0f);
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(machine.coprocessor1.getValue(14)).isEqualTo(previousValues[14]);

      for (int initial = 0; initial <= 1; initial++) {
        for (boolean set : new boolean[] {true, false}) {
          // Whole-flag setters have historically bypassed undo recording.
          if (initial == 0) {
            machine.coprocessor1.clearConditionFlags();
          } else {
            machine.coprocessor1.setConditionFlags();
          }
          int flagsBefore = machine.coprocessor1.getConditionFlags();
          assertThat(backStepper.empty()).isTrue();
          machine.registerFile.initializeProgramCounter(pc);
          int old =
              set
                  ? TestSessions.execution(machine).setConditionFlag(flag)
                  : TestSessions.execution(machine).clearConditionFlag(flag);
          assertThat(old).isEqualTo(initial);
          assertThat(machine.coprocessor1.getConditionFlag(flag)).isEqualTo(set ? 1 : 0);
          assertThat(backStepper.empty()).isFalse();
          backStepper.backStep();
          assertThat(machine.coprocessor1.getConditionFlags()).isEqualTo(flagsBefore);
          assertThat(backStepper.empty()).isTrue();
        }
      }

      // Conversion helpers also retain their original behavior of bypassing undo recording.
      machine.coprocessor1.setRegisterToFloat("$f0", 1.5f);
      machine.coprocessor1.setRegisterToInt("$f2", 0x7fc12345);
      machine.coprocessor1.setRegisterPairToDouble("$f30", -2.5);
      assertThat(machine.coprocessor1.getFloatFromRegister("$f0")).isEqualTo(1.5f);
      assertThat(machine.coprocessor1.getIntFromRegister("$f2")).isEqualTo(0x7fc12345);
      assertThat(machine.coprocessor1.getDoubleFromRegisterPair("$f30")).isEqualTo(-2.5);
      assertThat(backStepper.empty()).isTrue();
    } finally {
      for (int i = 0; i < 32; i++) {
        machine.coprocessor1.setRegisterToInt(i, previousValues[i]);
      }
      if ((previousFlags & ~0xff) == 0) {
        machine.coprocessor1.clearConditionFlags();
      } else {
        machine.coprocessor1.setConditionFlags();
      }
      for (int i = 0; i < 8; i++) {
        if ((previousFlags & (1 << i)) == 0) {
          TestSessions.execution(machine).clearConditionFlag(i);
        } else {
          TestSessions.execution(machine).setConditionFlag(i);
        }
      }
      machine.registerFile.initializeProgramCounter(previousPc);
    }
  }
}
