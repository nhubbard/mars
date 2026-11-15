package edu.missouristate.mars

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryConfigurations
import edu.missouristate.mars.mips.hardware.MemoryState
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Characterization tests: verify that memory read/write at each segment boundary works correctly.
 * These tests pin address and alignment behavior across the segment implementations.
 */
internal class MemoryTest {
    private val machine: MarsContext = TestSessions.create()

    @AfterEach
    fun closeMachine() {
        machine.close()
    }

    private var memory: MemoryState? = null

    @BeforeEach
    fun resetState() {
        machine.memory.clear()
        machine.registerFile.resetRegisters()
        machine.coprocessor0.resetRegisters()
        machine.coprocessor1.resetRegisters()
        memory = machine.memory
    }

    // -------------------------------------------------------------------------
    // Data segment — .data base address (0x10010000)
    // -------------------------------------------------------------------------
    @Test
    @Throws(AddressErrorException::class)
    fun dataSegment_wordRoundTrip() {
        val addr = MemoryConfigurations.getDefaultConfiguration().dataBaseAddress
        memory!!.setWord(addr, -0x21524111)
        Assertions.assertThat(memory!!.getWord(addr)).isEqualTo(-0x21524111)
    }

    @Test
    @Throws(AddressErrorException::class)
    fun dataSegment_consecutiveWordsAreIndependent() {
        val base = MemoryConfigurations.getDefaultConfiguration().dataBaseAddress
        memory!!.setWord(base, 0x11111111)
        memory!!.setWord(base + 4, 0x22222222)
        Assertions.assertThat(memory!!.getWord(base)).isEqualTo(0x11111111)
        Assertions.assertThat(memory!!.getWord(base + 4)).isEqualTo(0x22222222)
    }

    @Test
    @Throws(AddressErrorException::class)
    fun dataSegment_halfWordRoundTrip() {
        val addr = MemoryConfigurations.getDefaultConfiguration().dataBaseAddress
        memory!!.setHalf(addr, 0xABCD)
        Assertions.assertThat(memory!!.getHalf(addr)).isEqualTo(0xABCD)
    }

    @Test
    @Throws(AddressErrorException::class)
    fun dataSegment_byteRoundTrip() {
        val addr = MemoryConfigurations.getDefaultConfiguration().dataBaseAddress
        memory!!.setByte(addr, 0x42)
        Assertions.assertThat(memory!!.getByte(addr)).isEqualTo(0x42)
    }

    // -------------------------------------------------------------------------
    // Data segment — word written as bytes, read back as word
    // -------------------------------------------------------------------------
    @Test
    @Throws(AddressErrorException::class)
    fun dataSegment_bytesComposeWord() {
        val base = MemoryConfigurations.getDefaultConfiguration().dataBaseAddress
        memory!!.setByte(base, 0x01)
        memory!!.setByte(base + 1, 0x02)
        memory!!.setByte(base + 2, 0x03)
        memory!!.setByte(base + 3, 0x04)
        // MARS stores bytes little-endian internally: byte at lowest address is LSB of the word.
        // Byte 0x01 at base → bits 7:0, byte 0x04 at base+3 → bits 31:24.
        val word = memory!!.getWord(base)
        Assertions.assertThat(word).isEqualTo(0x04030201)
    }

    // -------------------------------------------------------------------------
    // MemoryState clears to zero
    // -------------------------------------------------------------------------
    @Test
    @Throws(AddressErrorException::class)
    fun afterClear_dataSegmentReadsZero() {
        val addr = MemoryConfigurations.getDefaultConfiguration().dataBaseAddress
        memory!!.setWord(addr, -0x35014542)
        memory!!.clear()
        Assertions.assertThat(memory!!.getWord(addr)).isEqualTo(0)
    }

    // -------------------------------------------------------------------------
    // Heap base address
    // -------------------------------------------------------------------------
    @Test
    @Throws(AddressErrorException::class)
    fun heapBase_wordRoundTrip() {
        val addr = MemoryConfigurations.getDefaultConfiguration().heapBaseAddress
        memory!!.setWord(addr, 0x12345678)
        Assertions.assertThat(memory!!.getWord(addr)).isEqualTo(0x12345678)
    }

    // -------------------------------------------------------------------------
    // Alignment errors
    // -------------------------------------------------------------------------
    @Test
    fun unalignedWordAccess_throwsAddressErrorException() {
        val misaligned = (MemoryConfigurations.getDefaultConfiguration().dataBaseAddress + 1)
        Assertions.assertThatThrownBy(
                ThrowableAssert.ThrowingCallable { memory!!.setWord(misaligned, 0) },
            )
            .isInstanceOf(AddressErrorException::class.java)
    }

    @Test
    fun unalignedHalfWordAccess_throwsAddressErrorException() {
        val misaligned = (MemoryConfigurations.getDefaultConfiguration().dataBaseAddress + 1)
        Assertions.assertThatThrownBy(
                ThrowableAssert.ThrowingCallable { memory!!.setHalf(misaligned, 0) },
            )
            .isInstanceOf(AddressErrorException::class.java)
    }

    // -------------------------------------------------------------------------
    // Kernel data segment
    // -------------------------------------------------------------------------
    @Test
    @Throws(AddressErrorException::class)
    fun kernelDataSegment_wordRoundTrip() {
        val addr = MemoryConfigurations.getDefaultConfiguration().kernelDataBaseAddress
        memory!!.setWord(addr, -0x1120532)
        Assertions.assertThat(memory!!.getWord(addr)).isEqualTo(-0x1120532)
    }
}
