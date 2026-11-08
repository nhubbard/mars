package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.mips.instructions.basic.InstrAddS;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LegacyCoprocessor1Test {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void facadePreservesRegisterAndConditionFlagBackstepping(int flag) throws Exception {
    Globals.initialize();
    var previousProgram = Globals.program;
    var previousSymbols = Globals.symbolTable;
    int previousPc = RegisterFile.getProgramCounter();
    int previousFlags = Coprocessor1.getConditionFlags();
    int[] previousValues = new int[32];
    for (int i = 0; i < 32; i++) {
      previousValues[i] = Coprocessor1.getValue(i);
    }
    try {
      Globals.symbolTable = new SymbolTable();
      var program = new LegacyProgram();
      String path = SimulatorSmokeTest.resourcePath("01_helloworld.asm");
      program.readSource(path);
      program.tokenize();
      var filenames = new ArrayList<String>();
      filenames.add(path);
      program.assemble(program.prepareFilesForAssembly(filenames, path, null), true, false);
      Globals.program = program;
      var backStepper = program.getBackStepper();
      int pc = RegisterFile.getInitialProgramCounter() + 4;
      RegisterFile.initializeProgramCounter(pc);
      var independent = new Coprocessor1State();
      independent.updateRegister(12, 101);
      independent.setConditionFlag(flag);
      assertThat(backStepper.empty()).isTrue();
      assertThat(Coprocessor1.updateRegister(12, 42)).isEqualTo(previousValues[12]);
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(Coprocessor1.getValue(12)).isEqualTo(previousValues[12]);
      assertThat(independent.getValue(12)).isEqualTo(101);
      assertThat(independent.getConditionFlag(flag)).isEqualTo(1);

      Coprocessor1.setRegisterToFloat(12, 1.5f);
      RegisterFile.initializeProgramCounter(pc);
      var add = new InstrAddS();
      var statement =
          new ProgramStatement(null, "add.s $f14,$f12,$f12", null, null, add, pc - 4, 1);
      System.arraycopy(new int[] {14, 12, 12}, 0, statement.getOperands(), 0, 3);
      add.getSimulationCode().simulate(statement, Globals.instructionContext());
      assertThat(Coprocessor1.getFloatFromRegister(14)).isEqualTo(3.0f);
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(Coprocessor1.getValue(14)).isEqualTo(previousValues[14]);

      for (int initial = 0; initial <= 1; initial++) {
        for (boolean set : new boolean[] {true, false}) {
          // Whole-flag setters have historically bypassed undo recording.
          if (initial == 0) {
            Coprocessor1.clearConditionFlags();
          } else {
            Coprocessor1.setConditionFlags();
          }
          int flagsBefore = Coprocessor1.getConditionFlags();
          assertThat(backStepper.empty()).isTrue();
          RegisterFile.initializeProgramCounter(pc);
          int old =
              set ? Coprocessor1.setConditionFlag(flag) : Coprocessor1.clearConditionFlag(flag);
          assertThat(old).isEqualTo(initial);
          assertThat(Coprocessor1.getConditionFlag(flag)).isEqualTo(set ? 1 : 0);
          assertThat(backStepper.empty()).isFalse();
          backStepper.backStep();
          assertThat(Coprocessor1.getConditionFlags()).isEqualTo(flagsBefore);
          assertThat(backStepper.empty()).isTrue();
        }
      }

      // Conversion helpers also retain their original behavior of bypassing undo recording.
      Coprocessor1.setRegisterToFloat("$f0", 1.5f);
      Coprocessor1.setRegisterToInt("$f2", 0x7fc12345);
      Coprocessor1.setRegisterPairToDouble("$f30", -2.5);
      assertThat(Coprocessor1.getFloatFromRegister("$f0")).isEqualTo(1.5f);
      assertThat(Coprocessor1.getIntFromRegister("$f2")).isEqualTo(0x7fc12345);
      assertThat(Coprocessor1.getDoubleFromRegisterPair("$f30")).isEqualTo(-2.5);
      assertThat(backStepper.empty()).isTrue();
    } finally {
      Globals.program = null;
      for (int i = 0; i < 32; i++) {
        Coprocessor1.setRegisterToInt(i, previousValues[i]);
      }
      if ((previousFlags & ~0xff) == 0) {
        Coprocessor1.clearConditionFlags();
      } else {
        Coprocessor1.setConditionFlags();
      }
      for (int i = 0; i < 8; i++) {
        if ((previousFlags & (1 << i)) == 0) {
          Coprocessor1.clearConditionFlag(i);
        } else {
          Coprocessor1.setConditionFlag(i);
        }
      }
      RegisterFile.initializeProgramCounter(previousPc);
      Globals.program = previousProgram;
      Globals.symbolTable = previousSymbols;
    }
  }
}
