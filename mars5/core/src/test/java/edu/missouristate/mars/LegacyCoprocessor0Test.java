package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.mips.hardware.Coprocessor0;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LegacyCoprocessor0Test {
  @ParameterizedTest
  @ValueSource(ints = {8, 12, 13, 14})
  void facadePreservesBacksteppingAndNamedWritesRemainUnrecorded(int number) throws Exception {
    Globals.initialize();
    var previousProgram = Globals.program;
    var previousSymbols = Globals.symbolTable;
    int previousPc = RegisterFile.getProgramCounter();
    int previousValue = Coprocessor0.getValue(number);
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
      RegisterFile.initializeProgramCounter(RegisterFile.getInitialProgramCounter() + 4);

      var independent = new Coprocessor0State();
      independent.updateRegister(number, 101);
      assertThat(backStepper.empty()).isTrue();
      assertThat(Coprocessor0.getValue(number)).isEqualTo(previousValue);
      assertThat(Coprocessor0.updateRegister(number, 42)).isEqualTo(previousValue);
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(Coprocessor0.getValue(number)).isEqualTo(previousValue);
      assertThat(independent.getValue(number)).isEqualTo(101);
      assertThat(backStepper.empty()).isTrue();

      // The original string overload updates the register without recording undo history.
      assertThat(Coprocessor0.updateRegister("$" + number, 43)).isEqualTo(previousValue);
      assertThat(Coprocessor0.getValue(number)).isEqualTo(43);
      assertThat(backStepper.empty()).isTrue();
    } finally {
      Globals.program = null;
      Coprocessor0.updateRegister(number, previousValue);
      RegisterFile.initializeProgramCounter(previousPc);
      Globals.program = previousProgram;
      Globals.symbolTable = previousSymbols;
    }
  }
}
