package edu.missouristate.mars;

import org.assertj.core.api.Assertions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Coprocessor0;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.Memory;
import edu.missouristate.mars.mips.hardware.MemoryConfigurations;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests: verify that memory read/write at each segment boundary works correctly.
 * These tests pin memory addressing semantics so that the Memory refactoring in Phase 2 (splitting
 * Memory.java into focused segment classes) cannot silently change boundary behavior.
 */
class MemoryTest {

  private Memory memory;

  @BeforeAll
  static void initMars() {
    Globals.initialize();
  }

  @BeforeEach
  void resetState() {
    Globals.memory.clear();
    RegisterFile.resetRegisters();
    Coprocessor0.resetRegisters();
    Coprocessor1.resetRegisters();
    Globals.symbolTable = new SymbolTable("global");
    memory = Globals.memory;
  }

  // -------------------------------------------------------------------------
  // Data segment — .data base address (0x10010000)
  // -------------------------------------------------------------------------

  @Test
  void dataSegment_wordRoundTrip() throws AddressErrorException {
    int addr = MemoryConfigurations.getDefaultConfiguration().getDataBaseAddress();
    memory.setWord(addr, 0xDEADBEEF);
    assertThat(memory.getWord(addr)).isEqualTo(0xDEADBEEF);
  }

  @Test
  void dataSegment_consecutiveWordsAreIndependent() throws AddressErrorException {
    int base = MemoryConfigurations.getDefaultConfiguration().getDataBaseAddress();
    memory.setWord(base, 0x11111111);
    memory.setWord(base + 4, 0x22222222);
    assertThat(memory.getWord(base)).isEqualTo(0x11111111);
    assertThat(memory.getWord(base + 4)).isEqualTo(0x22222222);
  }

  @Test
  void dataSegment_halfWordRoundTrip() throws AddressErrorException {
    int addr = MemoryConfigurations.getDefaultConfiguration().getDataBaseAddress();
    memory.setHalf(addr, 0xABCD);
    assertThat(memory.getHalf(addr)).isEqualTo(0xABCD);
  }

  @Test
  void dataSegment_byteRoundTrip() throws AddressErrorException {
    int addr = MemoryConfigurations.getDefaultConfiguration().getDataBaseAddress();
    memory.setByte(addr, 0x42);
    assertThat(memory.getByte(addr)).isEqualTo(0x42);
  }

  // -------------------------------------------------------------------------
  // Data segment — word written as bytes, read back as word
  // -------------------------------------------------------------------------

  @Test
  void dataSegment_bytesComposeWord() throws AddressErrorException {
    int base = MemoryConfigurations.getDefaultConfiguration().getDataBaseAddress();
    memory.setByte(base, 0x01);
    memory.setByte(base + 1, 0x02);
    memory.setByte(base + 2, 0x03);
    memory.setByte(base + 3, 0x04);
    // MARS stores bytes little-endian internally: byte at lowest address is LSB of the word.
    // Byte 0x01 at base → bits 7:0, byte 0x04 at base+3 → bits 31:24.
    int word = memory.getWord(base);
    Assertions.assertThat(word).isEqualTo(0x04030201);
  }

  // -------------------------------------------------------------------------
  // Memory clears to zero
  // -------------------------------------------------------------------------

  @Test
  void afterClear_dataSegmentReadsZero() throws AddressErrorException {
    int addr = MemoryConfigurations.getDefaultConfiguration().getDataBaseAddress();
    memory.setWord(addr, 0xCAFEBABE);
    memory.clear();
    assertThat(memory.getWord(addr)).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // Heap base address
  // -------------------------------------------------------------------------

  @Test
  void heapBase_wordRoundTrip() throws AddressErrorException {
    int addr = MemoryConfigurations.getDefaultConfiguration().getHeapBaseAddress();
    memory.setWord(addr, 0x12345678);
    assertThat(memory.getWord(addr)).isEqualTo(0x12345678);
  }

  // -------------------------------------------------------------------------
  // Alignment errors
  // -------------------------------------------------------------------------

  @Test
  void unalignedWordAccess_throwsAddressErrorException() {
    int misaligned = MemoryConfigurations.getDefaultConfiguration().getDataBaseAddress() + 1;
    Assertions.assertThatThrownBy(() -> memory.setWord(misaligned, 0))
        .isInstanceOf(AddressErrorException.class);
  }

  @Test
  void unalignedHalfWordAccess_throwsAddressErrorException() {
    int misaligned = MemoryConfigurations.getDefaultConfiguration().getDataBaseAddress() + 1;
    Assertions.assertThatThrownBy(() -> memory.setHalf(misaligned, 0))
        .isInstanceOf(AddressErrorException.class);
  }

  // -------------------------------------------------------------------------
  // Kernel data segment
  // -------------------------------------------------------------------------

  @Test
  void kernelDataSegment_wordRoundTrip() throws AddressErrorException {
    int addr = MemoryConfigurations.getDefaultConfiguration().getKernelDataBaseAddress();
    memory.setWord(addr, 0xFEEDFACE);
    assertThat(memory.getWord(addr)).isEqualTo(0xFEEDFACE);
  }
}
