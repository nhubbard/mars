package edu.missouristate.mars.mips.hardware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.core.SimulationSettings;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import edu.missouristate.mars.simulator.Exceptions;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MemoryStateTest {
  private static final InstructionSet instructions = new InstructionSet();

  @BeforeAll
  static void prepareDecoder() {
    instructions.populate();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Default", "CompactDataAtZero", "CompactTextAtZero"})
  void allSegmentsTextAndHeapBelongToTheirOwnInstance(String layout) throws Exception {
    var first = memory(layout, new SimulationSettings());
    var second = memory(layout, new SimulationSettings());
    for (int address :
        new int[] {
          first.dataBaseAddress,
          first.stackBaseAddress,
          first.kernelDataBaseAddress,
          first.memoryMapBaseAddress
        }) {
      first.setWord(address, 0x12345678);
      second.setWord(address, 0x76543210);
      assertThat(first.getWord(address)).as("address %08x", address).isEqualTo(0x12345678);
      assertThat(second.getWord(address)).isEqualTo(0x76543210);
    }
    var statement = new ProgramStatement(0x2408002a, first.textBaseAddress, instructions);
    first.setStatement(first.textBaseAddress, statement);
    assertThat(first.getStatement(first.textBaseAddress)).isSameAs(statement);
    assertThat(second.getStatement(second.textBaseAddress)).isNull();
    first.setStatement(
        first.kernelTextBaseAddress,
        new ProgramStatement(0, first.kernelTextBaseAddress, instructions));
    assertThat(second.getStatement(second.kernelTextBaseAddress)).isNull();
    int heap = first.allocateBytesFromHeap(5);
    assertThat(heap).isEqualTo(first.heapBaseAddress);
    assertThat(first.allocateBytesFromHeap(4)).isEqualTo(heap + 8);
    assertThat(second.allocateBytesFromHeap(4)).isEqualTo(second.heapBaseAddress);
    first.clear();
    assertThat(first.allocateBytesFromHeap(4)).isEqualTo(first.heapBaseAddress);
    assertThat(first.getStatement(first.textBaseAddress)).isNull();
    for (int address :
        new int[] {
          first.dataBaseAddress,
          first.stackBaseAddress,
          first.kernelDataBaseAddress,
          first.memoryMapBaseAddress
        }) {
      assertThat(first.getWord(address)).isZero();
      assertThat(second.getWord(address)).isEqualTo(0x76543210);
    }
  }

  @Test
  void layoutsAndConfigurationSnapshotsDoNotAffectOtherInstances() throws Exception {
    var configuration = MemoryConfigurations.createConfiguration("CompactDataAtZero");
    var first =
        new MemoryState(
            configuration,
            new SimulationSettings(),
            (binary, address) -> new ProgramStatement(binary, address, instructions));
    var second = memory("Default", new SimulationSettings());
    first.setWord(0, 42);
    second.setWord(second.dataBaseAddress, 99);
    configuration.configurationItemValues()[0] = 123;
    first.getConfiguration().configurationItemValues()[0] = 456;
    first.clear();
    assertThat(first.textBaseAddress).isEqualTo(0x3000);
    assertThat(first.stackPointer).isEqualTo(0x2ffc);
    assertThat(first.getConfiguration().getTextBaseAddress()).isEqualTo(0x3000);
    assertThat(second.textBaseAddress).isEqualTo(0x400000);
    assertThat(second.getWord(second.dataBaseAddress)).isEqualTo(99);
  }

  @Test
  void byteOrderIsIndependentAndRetainedWhenClearing() throws Exception {
    var first = memory("Default", new SimulationSettings());
    var second = memory("Default", new SimulationSettings());
    first.setByteOrder(MemoryState.BIG_ENDIAN);
    first.setRawWord(first.dataBaseAddress, 0x11223344);
    second.setRawWord(second.dataBaseAddress, 0x11223344);
    assertThat(first.getByte(first.dataBaseAddress)).isEqualTo(0x11);
    assertThat(second.getByte(second.dataBaseAddress)).isEqualTo(0x44);
    assertThat(first.getRawWord(first.dataBaseAddress)).isEqualTo(0x11223344);
    first.clear();
    assertThat(first.getByteOrder()).isEqualTo(MemoryState.BIG_ENDIAN);
    assertThat(second.getByteOrder()).isEqualTo(MemoryState.LITTLE_ENDIAN);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Default", "CompactDataAtZero", "CompactTextAtZero"})
  void textAndDataLimitsRetainTheirExclusiveBoundaryChecks(String layout) throws Exception {
    var state = memory(layout, new SimulationSettings());
    assertThat(state.inTextSegment(state.textBaseAddress)).isTrue();
    assertThat(state.inTextSegment(state.textLimitAddress)).isFalse();
    int lastTextWord = (state.textLimitAddress - 1) & ~3;
    state.setStatement(lastTextWord, new ProgramStatement(0, lastTextWord, instructions));
    assertThat(state.getStatement(lastTextWord)).isNotNull();
    assertThatExceptionOfType(AddressErrorException.class)
        .isThrownBy(() -> state.getStatement(state.textLimitAddress));
    assertThatExceptionOfType(AddressErrorException.class)
        .isThrownBy(() -> state.getStatement(state.textBaseAddress - 4));
    state.setByte(state.dataSegmentLimitAddress - 1, 0xab);
    assertThat(state.getByte(state.dataSegmentLimitAddress - 1)).isEqualTo(0xab);
    assertThatExceptionOfType(AddressErrorException.class)
        .isThrownBy(() -> state.getByte(state.dataSegmentLimitAddress));
    state.setByte(state.memoryMapLimitAddress - 1, 0xcd);
    assertThat(state.getByte(state.memoryMapLimitAddress - 1)).isEqualTo(0xcd);
    assertThatExceptionOfType(AddressErrorException.class)
        .isThrownBy(() -> state.getByte(state.memoryMapLimitAddress));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3})
  void unalignedHalfwordAccessReportsTheCorrectAddressAndCause(int offset) {
    var state = memory("Default", new SimulationSettings());
    int address = state.dataBaseAddress + offset;
    assertThatExceptionOfType(AddressErrorException.class)
        .isThrownBy(() -> state.setHalf(address, 0xabcd))
        .satisfies(
            error -> {
              assertThat(error.getAddress()).isEqualTo(address);
              assertThat(error.getType()).isEqualTo(Exceptions.ADDRESS_EXCEPTION_STORE);
            });
    assertThatExceptionOfType(AddressErrorException.class)
        .isThrownBy(() -> state.getHalf(address))
        .satisfies(
            error -> assertThat(error.getType()).isEqualTo(Exceptions.ADDRESS_EXCEPTION_LOAD));
  }

  @Test
  void writesOnBothSidesOfAnAllocationBlockBoundaryRemainIndependent() throws Exception {
    var first = memory("Default", new SimulationSettings());
    var second = memory("Default", new SimulationSettings());
    int address = first.dataBaseAddress + 4092;
    first.setWord(address, 0x11223344);
    first.setWord(address + 4, 0x55667788);
    assertThat(first.getByte(address + 3)).isEqualTo(0x11);
    assertThat(first.getByte(address + 4)).isEqualTo(0x88);
    assertThat(second.getRawWordOrNull(address)).isNull();
    assertThat(second.getWord(address + 4)).isZero();
  }

  @Test
  void failedHeapRequestsDoNotConsumeHeapSpace() {
    var state = memory("Default", new SimulationSettings());
    assertThatIllegalArgumentException().isThrownBy(() -> state.allocateBytesFromHeap(-1));
    assertThatIllegalArgumentException().isThrownBy(() -> state.allocateBytesFromHeap(0x400000));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> state.allocateBytesFromHeap(Integer.MAX_VALUE));
    assertThat(state.allocateBytesFromHeap(4)).isEqualTo(state.heapBaseAddress);
  }

  @Test
  void selfModifyingCodeUsesTheOwnersLiveSettingsAndDecoder() throws Exception {
    var settings = new SimulationSettings();
    var first = memory("Default", settings);
    var second = memory("Default", new SimulationSettings());
    int address = first.textBaseAddress;
    assertThatExceptionOfType(AddressErrorException.class)
        .isThrownBy(() -> first.setWord(address, 0x2408002a));
    settings.setSelfModifyingCodeEnabled(true);
    first.setWord(address, 0x2408002a);
    assertThat(first.getStatement(address).getInstruction().getName()).isEqualTo("addiu");
    assertThat(first.getStatement(address).getOperands()).startsWith(8, 0, 42);
    assertThat(first.getWord(address)).isEqualTo(0x2408002a);
    assertThat(second.getStatement(address)).isNull();
    assertThatExceptionOfType(AddressErrorException.class)
        .isThrownBy(() -> second.setWord(address, 0x2408002a));
    first.setWord(first.dataBaseAddress, 0x24090063);
    assertThat(first.getStatement(first.dataBaseAddress).getOperands()).startsWith(9, 0, 99);
  }

  @Test
  void listenersAreIsolatedFilteredAndRetainedAcrossClear() throws Exception {
    var first = memory("Default", new SimulationSettings());
    var second = memory("Default", new SimulationSettings());
    var notices = new ArrayList<MemoryAccessNotice>();
    Consumer<MemoryAccessNotice> listener = notices::add;
    int address = first.dataBaseAddress;
    first.addObserver(listener, address);
    second.setWord(address, 99);
    first.setWord(address + 4, 42);
    assertThat(notices).isEmpty();
    first.setWord(address, 123);
    first.getWord(address);
    first.getWordNoNotify(address);
    assertThat(notices)
        .extracting(MemoryAccessNotice::getAccessType)
        .containsExactly(AccessNotice.WRITE, AccessNotice.READ);
    first.clear();
    first.setWord(address, 456);
    assertThat(notices).hasSize(3);
    first.deleteObserver(listener);
    first.getWord(address);
    assertThat(notices).hasSize(3);
  }

  private static MemoryState memory(String layout, SimulationSettings settings) {
    return new MemoryState(
        MemoryConfigurations.createConfiguration(layout),
        settings,
        (binary, address) -> new ProgramStatement(binary, address, instructions));
  }
}
