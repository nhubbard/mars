package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.core.SimulationSettings;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.instructions.basic.InstrSw;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MemoryUndoIsolationTest {
  private edu.missouristate.mars.core.MarsContext machine = TestSessions.create();

  @org.junit.jupiter.api.AfterEach
  void closeMachine() {
    machine.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"word", "half", "byte", "raw", "instruction"})
  void legacyWritesKeepUndoHistoryWhileIndependentWritesDoNot(String operation) throws Exception {

    var previousProgram = machine.getProgram();
    var previousSymbols = machine.symbolTable;
    int previousPc = machine.registerFile.getProgramCounter();
    int previousT0 = machine.registerFile.getValue(8);
    int previousT1 = machine.registerFile.getValue(9);
    try {
      var program = new MIPSprogram(machine);
      String path = SimulatorSmokeTest.resourcePath("01_helloworld.asm");
      program.readSource(path);
      program.tokenize();
      var files = new ArrayList<String>();
      files.add(path);
      program.assemble(program.prepareFilesForAssembly(files, path, null), true, false);
      int address = machine.memory.dataBaseAddress + 0x100;
      TestSessions.execution(machine).setMemoryWord(address, 0x12345678);
      TestSessions.execution(machine).updateRegister(8, 0x76543210);
      TestSessions.execution(machine).updateRegister(9, address);
      machine.registerFile.initializeProgramCounter(machine.memory.textBaseAddress + 4);
      var backStepper = program.getBackStepper();
      machine.backStepper.clear();
      machine.backStepper.clear();
      var independent =
          new MemoryState(
              edu.missouristate.mars.mips.hardware.MemoryConfigurations.createConfiguration(
                  "Default"),
              new SimulationSettings(),
              (binary, pc) -> new ProgramStatement(binary, pc, machine.instructionSet));
      independent.setWord(address, 99);
      assertThat(backStepper.empty()).isTrue();
      switch (operation) {
        case "word" -> TestSessions.execution(machine).setMemoryWord(address, 0x76543210);
        case "half" -> TestSessions.execution(machine).setMemoryHalf(address, 0x3210);
        case "byte" -> TestSessions.execution(machine).setMemoryByte(address, 0x10);
        case "raw" -> TestSessions.execution(machine).setMemoryRawWord(address, 0x76543210);
        case "instruction" -> {
          var store = new InstrSw();
          var statement =
              new ProgramStatement(
                  null, "sw $t0,0($t1)", null, null, store, machine.memory.textBaseAddress, 1);
          System.arraycopy(new int[] {8, 0, 9}, 0, statement.getOperands(), 0, 3);
          store
              .getSimulationCode()
              .simulate(
                  statement,
                  new edu.missouristate.mars.core.InstructionContext(
                      machine, machine.syscallEnvironment));
        }
        default -> throw new IllegalArgumentException(operation);
      }
      assertThat(backStepper.empty()).isFalse();
      backStepper.backStep();
      assertThat(machine.memory.getWord(address)).isEqualTo(0x12345678);
      assertThat(independent.getWord(address)).isEqualTo(99);
    } finally {
      TestSessions.execution(machine).updateRegister(8, previousT0);
      TestSessions.execution(machine).updateRegister(9, previousT1);
      machine.registerFile.initializeProgramCounter(previousPc);
    }
  }

  @Test
  void legacyConfigurationAndHeapMirrorsStayCompatibleWithoutAffectingSessionMemory()
      throws Exception {

    var previousProgram = machine.getProgram();
    var previousConfiguration = machine.memory.getConfiguration();
    int previousPc = machine.registerFile.getProgramCounter();
    int[] previousValues = new int[35];
    for (int i = 0; i < 35; i++) {
      previousValues[i] = i == 32 ? previousPc : machine.registerFile.getValue(i);
    }
    try {
      var session =
          new MemoryState(
              edu.missouristate.mars.mips.hardware.MemoryConfigurations.createConfiguration(
                  "CompactTextAtZero"),
              new SimulationSettings(),
              (binary, address) -> new ProgramStatement(binary, address, machine.instructionSet));
      session.setWord(session.dataBaseAddress, 42);
      session.allocateBytesFromHeap(8);
      var singleton = machine.memory;
      TestSessions.select(
          machine,
          edu.missouristate.mars.mips.hardware.MemoryConfigurations.getConfigurationByName(
              "CompactDataAtZero"));
      assertThat(machine.memory).isSameAs(singleton);
      assertThat(machine.memory.textBaseAddress).isEqualTo(0x3000);
      assertThat(machine.memory.dataBaseAddress).isZero();
      assertThat(machine.memory.inTextSegment(0x3000)).isTrue();
      assertThat(machine.registerFile.getProgramCounter()).isEqualTo(0x3000);
      assertThat(machine.registerFile.getValue(29)).isEqualTo(0x2ffc);
      assertThat(machine.memory.allocateBytesFromHeap(5)).isEqualTo(0x2000);
      assertThat(machine.memory.heapAddress).isEqualTo(0x2008);
      machine.memory.clear();
      assertThat(machine.memory.heapAddress).isEqualTo(0x2000);
      assertThat(session.textBaseAddress).isZero();
      assertThat(session.getWord(session.dataBaseAddress)).isEqualTo(42);
      assertThat(session.heapAddress).isEqualTo(session.heapBaseAddress + 8);
    } finally {
      TestSessions.select(machine, previousConfiguration);
      for (int i = 1; i < 35; i++) {
        if (i != 32) {
          TestSessions.execution(machine).updateRegister(i, previousValues[i]);
        }
      }
      machine.registerFile.initializeProgramCounter(previousPc);
    }
  }
}
