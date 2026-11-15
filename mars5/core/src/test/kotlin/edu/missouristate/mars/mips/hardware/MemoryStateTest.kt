package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.core.SimulationSettings
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.simulator.Exceptions
import java.util.function.Consumer
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class MemoryStateTest {
    @ParameterizedTest
    @ValueSource(strings = ["Default", "CompactDataAtZero", "CompactTextAtZero"])
    fun allSegmentsTextAndHeapBelongToTheirOwnInstance(layout: String) {
        val first: MemoryState = memory(layout, SimulationSettings())
        val second: MemoryState = memory(layout, SimulationSettings())
        for (address in
            intArrayOf(
                first.dataBaseAddress,
                first.stackBaseAddress,
                first.kernelDataBaseAddress,
                first.memoryMapBaseAddress,
            )) {
            first.setWord(address, 0x12345678)
            second.setWord(address, 0x76543210)
            Assertions.assertThat(first.getWord(address))
                .`as`("address %08x", address)
                .isEqualTo(0x12345678)
            Assertions.assertThat(second.getWord(address)).isEqualTo(0x76543210)
        }
        val statement = ProgramStatement(0x2408002a, first.textBaseAddress, instructions)
        first.setStatement(first.textBaseAddress, statement)
        Assertions.assertThat(first.getStatement(first.textBaseAddress))
            .isSameAs(statement)
        Assertions.assertThat(second.getStatement(second.textBaseAddress))
            .isNull()
        first.setStatement(
            first.kernelTextBaseAddress,
            ProgramStatement(0, first.kernelTextBaseAddress, instructions),
        )
        Assertions.assertThat(second.getStatement(second.kernelTextBaseAddress))
            .isNull()
        val heap = first.allocateBytesFromHeap(5)
        Assertions.assertThat(heap).isEqualTo(first.heapBaseAddress)
        Assertions.assertThat(first.allocateBytesFromHeap(4)).isEqualTo(heap + 8)
        Assertions.assertThat(second.allocateBytesFromHeap(4)).isEqualTo(second.heapBaseAddress)
        first.clear()
        Assertions.assertThat(first.allocateBytesFromHeap(4)).isEqualTo(first.heapBaseAddress)
        Assertions.assertThat(first.getStatement(first.textBaseAddress)).isNull()
        for (address in
            intArrayOf(
                first.dataBaseAddress,
                first.stackBaseAddress,
                first.kernelDataBaseAddress,
                first.memoryMapBaseAddress,
            )) {
            Assertions.assertThat(first.getWord(address)).isZero()
            Assertions.assertThat(second.getWord(address)).isEqualTo(0x76543210)
        }
    }

    @Test
    fun inspectionReadsCodeAndMappedWordsWithoutNotifyingDevices() {
        val state: MemoryState = memory("Default", SimulationSettings())
        val data =
            intArrayOf(
                state.dataBaseAddress,
                state.stackBaseAddress,
                state.kernelDataBaseAddress,
                state.memoryMapBaseAddress,
            )
        for (address in data) {
            state.setWord(address, 0x12345678)
        }
        for (address in intArrayOf(state.textBaseAddress, state.kernelTextBaseAddress)) {
            state.setStatement(address, ProgramStatement(0x2408002a, address, instructions))
        }
        val notices = ArrayList<MemoryAccessNotice>()
        state.addObserver({ notices.add(it) })
        for (address in data) {
            Assertions.assertThat(state.inspectWord(address)).isEqualTo(0x12345678)
        }
        Assertions.assertThat(state.inspectWord(state.textBaseAddress)).isEqualTo(0x2408002a)
        Assertions.assertThat(state.inspectWord(state.kernelTextBaseAddress)).isEqualTo(0x2408002a)
        Assertions.assertThat(state.inspectWord(state.textBaseAddress + 4)).isNull()
        Assertions.assertThat(state.inspectWord(state.dataBaseAddress + 1)).isNull()
        Assertions.assertThat(state.inspectWord(state.dataBaseAddress + 4)).isZero()
        Assertions.assertThat(state.inspectWord(0)).isNull()
        Assertions.assertThat(notices).isEmpty()
    }

    @Test
    fun disassemblyInspectsOnlyExecutableMemoryWithoutNotifyingDevices() {
        val settings = SimulationSettings()
        val state: MemoryState = memory("Default", settings)
        val code = 0x2408002a
        state.setStatement(
            state.kernelTextBaseAddress,
            ProgramStatement(code, state.kernelTextBaseAddress, instructions),
        )
        state.setWord(state.dataBaseAddress, code)
        val notices = ArrayList<MemoryAccessNotice>()
        state.addObserver({ notices.add(it) })
        Assertions.assertThat(
                requireNotNull(state.inspectInstruction(state.kernelTextBaseAddress))
                    .binaryStatement,
            )
            .isEqualTo(code)
        Assertions.assertThat(state.inspectInstruction(state.textBaseAddress))
            .isNull()
        Assertions.assertThat(state.inspectInstruction(state.dataBaseAddress))
            .isNull()
        settings.setSelfModifyingCodeEnabled(true)
        val decoded = requireNotNull(state.inspectInstruction(state.dataBaseAddress))
        Assertions.assertThat(decoded.binaryStatement).isEqualTo(code)
        Assertions.assertThat(decoded.instructionOrigin).isNull()
        Assertions.assertThat(decoded.printableBasicAssemblyStatement).contains("addiu")
        Assertions.assertThat(
                state.inspectInstruction(state.dataBaseAddress + 1),
            )
            .isNull()
        Assertions.assertThat(state.inspectInstruction(0)).isNull()
        Assertions.assertThat(notices).isEmpty()
    }

    @Test
    fun layoutsAndConfigurationSnapshotsDoNotAffectOtherInstances() {
        val configuration = MemoryConfigurations.createConfiguration("CompactDataAtZero")
        val first =
            MemoryState(
                configuration,
                SimulationSettings(),
                { binary, address -> ProgramStatement(binary, address, instructions) },
            )
        val second: MemoryState = memory("Default", SimulationSettings())
        first.setWord(0, 42)
        second.setWord(second.dataBaseAddress, 99)
        configuration.configurationItemValues()[0] = 123
        first.getConfiguration().configurationItemValues()[0] = 456
        first.clear()
        Assertions.assertThat(first.textBaseAddress).isEqualTo(0x3000)
        Assertions.assertThat(first.stackPointer).isEqualTo(0x2ffc)
        Assertions.assertThat(first.getConfiguration().textBaseAddress).isEqualTo(0x3000)
        Assertions.assertThat(second.textBaseAddress).isEqualTo(0x400000)
        Assertions.assertThat(second.getWord(second.dataBaseAddress)).isEqualTo(99)
    }

    @Test
    fun byteOrderIsIndependentAndRetainedWhenClearing() {
        val first: MemoryState = memory("Default", SimulationSettings())
        val second: MemoryState = memory("Default", SimulationSettings())
        first.byteOrder = MemoryState.BIG_ENDIAN
        first.setRawWord(first.dataBaseAddress, 0x11223344)
        second.setRawWord(second.dataBaseAddress, 0x11223344)
        Assertions.assertThat(first.getByte(first.dataBaseAddress)).isEqualTo(0x11)
        Assertions.assertThat(second.getByte(second.dataBaseAddress)).isEqualTo(0x44)
        Assertions.assertThat(first.getRawWord(first.dataBaseAddress)).isEqualTo(0x11223344)
        first.clear()
        Assertions.assertThat(first.byteOrder).isEqualTo(MemoryState.BIG_ENDIAN)
        Assertions.assertThat(second.byteOrder).isEqualTo(MemoryState.LITTLE_ENDIAN)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Default", "CompactDataAtZero", "CompactTextAtZero"])
    fun textAndDataLimitsRetainTheirExclusiveBoundaryChecks(layout: String) {
        val state: MemoryState = memory(layout, SimulationSettings())
        Assertions.assertThat(state.inTextSegment(state.textBaseAddress)).isTrue()
        Assertions.assertThat(state.inTextSegment(state.textLimitAddress)).isFalse()
        val lastTextWord = (state.textLimitAddress - 1) and 3.inv()
        state.setStatement(lastTextWord, ProgramStatement(0, lastTextWord, instructions))
        Assertions.assertThat(state.getStatement(lastTextWord)).isNotNull()
        Assertions.assertThatExceptionOfType(AddressErrorException::class.java)
            .isThrownBy { state.getStatement(state.textLimitAddress) }
        Assertions.assertThatExceptionOfType(AddressErrorException::class.java)
            .isThrownBy { state.getStatement(state.textBaseAddress - 4) }
        state.setByte(state.dataSegmentLimitAddress - 1, 0xab)
        Assertions.assertThat(state.getByte(state.dataSegmentLimitAddress - 1)).isEqualTo(0xab)
        Assertions.assertThatExceptionOfType(AddressErrorException::class.java)
            .isThrownBy { state.getByte(state.dataSegmentLimitAddress) }
        state.setByte(state.memoryMapLimitAddress - 1, 0xcd)
        Assertions.assertThat(state.getByte(state.memoryMapLimitAddress - 1)).isEqualTo(0xcd)
        Assertions.assertThatExceptionOfType(AddressErrorException::class.java)
            .isThrownBy { state.getByte(state.memoryMapLimitAddress) }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3])
    fun unalignedHalfwordAccessReportsTheCorrectAddressAndCause(offset: Int) {
        val state: MemoryState = memory("Default", SimulationSettings())
        val address = state.dataBaseAddress + offset
        Assertions.assertThatExceptionOfType(AddressErrorException::class.java)
            .isThrownBy { state.setHalf(address, 0xabcd) }
            .satisfies(
                ThrowingConsumer { error: AddressErrorException ->
                    Assertions.assertThat(error.address).isEqualTo(address)
                    Assertions.assertThat(error.type).isEqualTo(Exceptions.ADDRESS_EXCEPTION_STORE)
                },
            )
        Assertions.assertThatExceptionOfType(AddressErrorException::class.java)
            .isThrownBy { state.getHalf(address) }
            .satisfies(
                ThrowingConsumer { error: AddressErrorException ->
                    Assertions.assertThat(error.type).isEqualTo(Exceptions.ADDRESS_EXCEPTION_LOAD)
                },
            )
    }

    @Test
    fun writesOnBothSidesOfAnAllocationBlockBoundaryRemainIndependent() {
        val first: MemoryState = memory("Default", SimulationSettings())
        val second: MemoryState = memory("Default", SimulationSettings())
        val address = first.dataBaseAddress + 4092
        first.setWord(address, 0x11223344)
        first.setWord(address + 4, 0x55667788)
        Assertions.assertThat(first.getByte(address + 3)).isEqualTo(0x11)
        Assertions.assertThat(first.getByte(address + 4)).isEqualTo(0x88)
        Assertions.assertThat(second.getRawWordOrNull(address)).isNull()
        Assertions.assertThat(second.getWord(address + 4)).isZero()
    }

    @Test
    fun failedHeapRequestsDoNotConsumeHeapSpace() {
        val state: MemoryState = memory("Default", SimulationSettings())
        Assertions.assertThatIllegalArgumentException()
            .isThrownBy { state.allocateBytesFromHeap(-1) }
        Assertions.assertThatIllegalArgumentException()
            .isThrownBy { state.allocateBytesFromHeap(0x400000) }
        Assertions.assertThatIllegalArgumentException()
            .isThrownBy { state.allocateBytesFromHeap(Int.MAX_VALUE) }
        Assertions.assertThat(state.allocateBytesFromHeap(4)).isEqualTo(state.heapBaseAddress)
    }

    @Test
    fun selfModifyingCodeUsesTheOwnersLiveSettingsAndDecoder() {
        val settings = SimulationSettings()
        val first: MemoryState = memory("Default", settings)
        val second: MemoryState = memory("Default", SimulationSettings())
        val address = first.textBaseAddress
        Assertions.assertThatExceptionOfType(AddressErrorException::class.java)
            .isThrownBy { first.setWord(address, 0x2408002a) }
        settings.setSelfModifyingCodeEnabled(true)
        first.setWord(address, 0x2408002a)
        val modified = requireNotNull(first.getStatement(address))
        Assertions.assertThat(requireNotNull(modified.instruction).name).isEqualTo("addiu")
        Assertions.assertThat(modified.operands).startsWith(8, 0, 42)
        Assertions.assertThat(first.getWord(address)).isEqualTo(0x2408002a)
        Assertions.assertThat(second.getStatement(address)).isNull()
        Assertions.assertThatExceptionOfType(AddressErrorException::class.java)
            .isThrownBy { second.setWord(address, 0x2408002a) }
        first.setWord(first.dataBaseAddress, 0x24090063)
        Assertions.assertThat(requireNotNull(first.getStatement(first.dataBaseAddress)).operands)
            .startsWith(9, 0, 99)
    }

    @Test
    fun listenersAreIsolatedFilteredAndRetainedAcrossClear() {
        val first: MemoryState = memory("Default", SimulationSettings())
        val second: MemoryState = memory("Default", SimulationSettings())
        val notices = ArrayList<MemoryAccessNotice>()
        val listener = Consumer<MemoryAccessNotice> { notices.add(it) }
        val address = first.dataBaseAddress
        first.addObserver(listener, address)
        second.setWord(address, 99)
        first.setWord(address + 4, 42)
        Assertions.assertThat(notices).isEmpty()
        first.setWord(address, 123)
        first.getWord(address)
        first.getWordNoNotify(address)
        Assertions.assertThat(notices)
            .extracting<Int?, RuntimeException>(AccessNotice::accessType)
            .containsExactly(AccessNotice.WRITE, AccessNotice.READ)
        first.clear()
        first.setWord(address, 456)
        Assertions.assertThat(notices).hasSize(3)
        first.deleteObserver(listener)
        first.getWord(address)
        Assertions.assertThat(notices).hasSize(3)
    }

    companion object {
        private val instructions = InstructionSet()

        @JvmStatic
        @BeforeAll
        fun prepareDecoder() {
            instructions.populate()
        }

        private fun memory(layout: String, settings: SimulationSettings): MemoryState {
            return MemoryState(
                MemoryConfigurations.createConfiguration(layout),
                settings,
                { binary, address -> ProgramStatement(binary, address, instructions) },
            )
        }
    }
}
