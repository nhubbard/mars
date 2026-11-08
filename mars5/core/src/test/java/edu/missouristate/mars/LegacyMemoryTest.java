package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.core.SimulationSettings;
import edu.missouristate.mars.mips.hardware.Memory;
import edu.missouristate.mars.mips.hardware.MemoryConfigurations;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.mips.instructions.basic.InstrSw;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LegacyMemoryTest {
  @ParameterizedTest
  @ValueSource(strings = {"word", "half", "byte", "raw", "instruction"})
  void legacyWritesKeepUndoHistoryWhileIndependentWritesDoNot(String operation) throws Exception {
    Globals.initialize();
    var previousProgram = Globals.program;
    var previousSymbols = Globals.symbolTable;
    int previousPc = RegisterFile.getProgramCounter();
    int previousT0 = RegisterFile.getValue(8);
    int previousT1 = RegisterFile.getValue(9);
    try {
      Globals.program = null;
      Globals.symbolTable = new SymbolTable("global");
      var program = new MIPSprogram();
      String path = SimulatorSmokeTest.resourcePath("01_helloworld.asm");
      program.readSource(path);
      program.tokenize();
      var files = new ArrayList<String>();
      files.add(path);
      program.assemble(program.prepareFilesForAssembly(files, path, null), true, false);
      int address = Memory.dataBaseAddress + 0x100;
      Globals.memory.setWord(address, 0x12345678);
      RegisterFile.updateRegister(8, 0x76543210);
      RegisterFile.updateRegister(9, address);
      RegisterFile.initializeProgramCounter(Memory.textBaseAddress + 4);
      Globals.program = program;
      var backStepper = program.getBackStepper();
      var independent =
          new MemoryState(
              MemoryConfigurations.createConfiguration("Default"),
              new SimulationSettings(),
              (binary, pc) -> new ProgramStatement(binary, pc, Globals.instructionSet));
      independent.setWord(address, 99);
      assertThat(backStepper.empty()).isTrue();
      switch (operation) {
        case "word" -> Globals.memory.setWord(address, 0x76543210);
        case "half" -> Globals.memory.setHalf(address, 0x3210);
        case "byte" -> Globals.memory.setByte(address, 0x10);
        case "raw" -> Globals.memory.setRawWord(address, 0x76543210);
        case "instruction" -> {
          var store = new InstrSw();
          var statement =
              new ProgramStatement(
                  null, "sw $t0,0($t1)", null, null, store, Memory.textBaseAddress, 1);
          System.arraycopy(new int[] {8, 0, 9}, 0, statement.getOperands(), 0, 3);
          store.getSimulationCode().simulate(statement);
        }
        default -> throw new IllegalArgumentException(operation);
      }
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(Globals.memory.getWord(address)).isEqualTo(0x12345678);
      assertThat(independent.getWord(address)).isEqualTo(99);
    } finally {
      Globals.program = null;
      RegisterFile.updateRegister(8, previousT0);
      RegisterFile.updateRegister(9, previousT1);
      RegisterFile.initializeProgramCounter(previousPc);
      Globals.program = previousProgram;
      Globals.symbolTable = previousSymbols;
    }
  }

  @Test
  void legacyConfigurationAndHeapMirrorsStayCompatibleWithoutAffectingSessionMemory()
      throws Exception {
    Globals.initialize();
    var previousProgram = Globals.program;
    var previousConfiguration = MemoryConfigurations.getCurrentConfiguration();
    int previousPc = RegisterFile.getProgramCounter();
    int[] previousValues = new int[35];
    for (int i = 0; i < 35; i++) {
      previousValues[i] = i == 32 ? previousPc : RegisterFile.getValue(i);
    }
    try {
      Globals.program = null;
      var session =
          new MemoryState(
              MemoryConfigurations.createConfiguration("CompactTextAtZero"),
              new SimulationSettings(),
              (binary, address) -> new ProgramStatement(binary, address, Globals.instructionSet));
      session.setWord(session.dataBaseAddress, 42);
      session.allocateBytesFromHeap(8);
      var singleton = Memory.getInstance();
      MemoryConfigurations.setCurrentConfiguration(
          MemoryConfigurations.getConfigurationByName("CompactDataAtZero"));
      assertThat(Globals.memory).isSameAs(singleton);
      assertThat(Memory.textBaseAddress).isEqualTo(0x3000);
      assertThat(Memory.dataBaseAddress).isZero();
      assertThat(Memory.inTextSegment(0x3000)).isTrue();
      assertThat(RegisterFile.getProgramCounter()).isEqualTo(0x3000);
      assertThat(RegisterFile.getValue(29)).isEqualTo(0x2ffc);
      assertThat(Globals.memory.allocateBytesFromHeap(5)).isEqualTo(0x2000);
      assertThat(Memory.heapAddress).isEqualTo(0x2008);
      Globals.memory.clear();
      assertThat(Memory.heapAddress).isEqualTo(0x2000);
      assertThat(session.textBaseAddress).isZero();
      assertThat(session.getWord(session.dataBaseAddress)).isEqualTo(42);
      assertThat(session.heapAddress).isEqualTo(session.heapBaseAddress + 8);
    } finally {
      MemoryConfigurations.setCurrentConfiguration(previousConfiguration);
      for (int i = 1; i < 35; i++) {
        if (i != 32) RegisterFile.updateRegister(i, previousValues[i]);
      }
      RegisterFile.initializeProgramCounter(previousPc);
      Globals.program = previousProgram;
    }
  }
}
