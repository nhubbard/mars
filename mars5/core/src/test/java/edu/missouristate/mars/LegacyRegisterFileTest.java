package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class LegacyRegisterFileTest {
  @Test
  void facadePreservesBacksteppingWithoutRecordingIndependentInstanceWrites() throws Exception {
    Globals.initialize();
    var previousProgram = Globals.program;
    var previousSymbols = Globals.symbolTable;
    int[] previousValues = new int[35];
    for (int i = 0; i < 35; i++) {
      previousValues[i] = i == 32 ? RegisterFile.getProgramCounter() : RegisterFile.getValue(i);
    }
    try {
      Globals.symbolTable = new SymbolTable("global");
      var program = new MIPSprogram();
      String path = SimulatorSmokeTest.resourcePath("01_helloworld.asm");
      program.readSource(path);
      program.tokenize();
      var filenames = new ArrayList<String>();
      filenames.add(path);
      program.assemble(program.prepareFilesForAssembly(filenames, path, null), true, false);
      Globals.program = program;
      var backStepper = program.getBackStepper();
      int pc = RegisterFile.getInitialProgramCounter();
      RegisterFile.initializeProgramCounter(pc + 4);
      int previousT0 = RegisterFile.getValue(8);
      int previousHi = RegisterFile.getValue(33);
      int previousLo = RegisterFile.getValue(34);

      var independent = new IntegerRegisterFile();
      independent.setValue(8, 99);
      independent.setValue(33, 100);
      independent.setValue(32, 101);
      assertThat(backStepper.empty()).isTrue();
      assertThat(RegisterFile.getValue(8)).isEqualTo(previousT0);
      assertThat(RegisterFile.updateRegister(8, 42)).isEqualTo(previousT0);
      RegisterFile.updateRegister(33, 43);
      RegisterFile.updateRegister(34, 44);
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(RegisterFile.getValue(8)).isEqualTo(previousT0);
      assertThat(RegisterFile.getValue(33)).isEqualTo(previousHi);
      assertThat(RegisterFile.getValue(34)).isEqualTo(previousLo);
      assertThat(independent.getValue(8)).isEqualTo(99);

      RegisterFile.initializeProgramCounter(pc + 4);
      assertThat(RegisterFile.setProgramCounter(pc + 8)).isEqualTo(pc + 4);
      backStepper.backStep();
      // The legacy back-stepper restores the address before the automatic PC increment.
      assertThat(RegisterFile.getProgramCounter()).isEqualTo(pc);
      RegisterFile.updateRegister(0, 1000);
      assertThat(RegisterFile.getValue(0)).isZero();
    } finally {
      Globals.program = null;
      for (int i = 1; i < 35; i++) {
        if (i == 32) RegisterFile.initializeProgramCounter(previousValues[i]);
        else RegisterFile.updateRegister(i, previousValues[i]);
      }
      Globals.program = previousProgram;
      Globals.symbolTable = previousSymbols;
    }
  }
}
