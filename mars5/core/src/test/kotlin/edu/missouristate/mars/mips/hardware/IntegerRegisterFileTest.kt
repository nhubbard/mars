package edu.missouristate.mars.mips.hardware

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class IntegerRegisterFileTest {
    @Test
    fun instancesOwnAllRegistersAndResetValues() {
        val first = IntegerRegisterFile(100, 200, 300)
        val second = IntegerRegisterFile(400, 500, 600)
        for (i in 0..34) {
            first.setValue(i, i + 1000)
        }
        Assertions.assertThat(first.getValue(0)).isZero()
        for (i in 0..34) {
            val expected =
                when (i) {
                    28 -> 400
                    29 -> 500
                    32 -> 600
                    else -> 0
                }
            Assertions.assertThat(second.getValue(i)).`as`("register %s", i).isEqualTo(expected)
        }
        first.reset()
        Assertions.assertThat(first.getValue(28)).isEqualTo(100)
        Assertions.assertThat(first.getValue(29)).isEqualTo(200)
        Assertions.assertThat(first.getPC()).isEqualTo(300)
        Assertions.assertThat(first.getValue(33)).isZero()
        Assertions.assertThat(first.getValue(34)).isZero()
    }

    @Test
    fun namesAndNumbersReferToTheSameRegister() {
        val registers = IntegerRegisterFile()
        Assertions.assertThat(registers.getNumber($$"$t0")).isEqualTo(8)
        Assertions.assertThat(registers.getUserRegister("$8"))
            .isSameAs(registers.getUserRegister($$"$t0"))
        registers.updateRegister($$"$t0", 42)
        Assertions.assertThat(registers.getValue(8)).isEqualTo(42)
        Assertions.assertThat(registers.updateRegister(8, 99)).isEqualTo(42)
        Assertions.assertThat(registers.getValue(8)).isEqualTo(99)
        Assertions.assertThat(registers.getNumber($$"$missing")).isEqualTo(-1)
        Assertions.assertThat(registers.getUserRegister($$"$missing")).isNull()
    }

    @Test
    fun registerLookupPreservesNumericAliasesAlongsideNamedRegisters() {
        val file = IntegerRegisterFile()
        val aliases = mapOf(
            "$" to 0,
            "$-0" to 0,
            "$+8" to 8,
            "$010" to 8,
            "$0x8" to 8,
            "$#8" to 8,
            "$4294967304" to 8,
            "$\u0660\u0668" to 8,
            "$\u0663\u0661" to 31,
        )
        for ((spelling, number) in aliases) {
            Assertions.assertThat(file.getUserRegister(spelling)).isSameAs(file.registers[number])
        }
        for (register in file.registers) {
            Assertions.assertThat(file.getUserRegister(register.name)).isSameAs(register)
        }
        Assertions.assertThat(file.getUserRegister("$0x")).isNull()
        Assertions.assertThat(file.getUserRegister("$32")).isNull()
    }

    @Test
    fun programCounterSupportsInitializationIncrementAndRestore() {
        val registers = IntegerRegisterFile(1, 2, 0x400000)
        registers.initializeProgramCounter(0x400100)
        registers.incrementPC()
        Assertions.assertThat(registers.setProgramCounter(0x400200)).isEqualTo(0x400104)
        Assertions.assertThat(registers.getValue(32)).isEqualTo(0x400200)
        registers.setValue(32, 0x400300)
        Assertions.assertThat(registers.getPC()).isEqualTo(0x400300)
        registers.reset()
        Assertions.assertThat(registers.getPC()).isEqualTo(0x400000)
    }

    @Test
    fun observersOnlyReceiveTheirOwnInstancesAccessesAndCanUnsubscribe() {
        val first = IntegerRegisterFile()
        val second = IntegerRegisterFile()
        val notices = ArrayList<RegisterAccessNotice>()
        val listener: (RegisterAccessNotice) -> Unit = { notices.add(it) }
        first.addRegistersObserver(listener)
        second.setValue(8, 123)
        Assertions.assertThat(notices).isEmpty()
        first.setValue(8, 42)
        first.getValue(8)
        first.setValue(33, 5)
        first.setValue(34, 6)
        Assertions.assertThat(first.getValueNoNotify(IntegerRegisterFile.HI_REGISTER)).isEqualTo(5)
        Assertions.assertThat(first.getValueNoNotify(IntegerRegisterFile.LO_REGISTER)).isEqualTo(6)
        Assertions.assertThat(notices)
            .extracting<Int?, RuntimeException>(AccessNotice::accessType)
            .containsExactly(
                AccessNotice.WRITE,
                AccessNotice.READ,
                AccessNotice.WRITE,
                AccessNotice.WRITE,
            )
        Assertions.assertThat(notices)
            .extracting<String?, RuntimeException>(RegisterAccessNotice::registerName)
            .containsExactly($$"$t0", $$"$t0", "hi", "lo")
        first.deleteRegistersObserver(listener)
        first.setValue(8, 99)
        Assertions.assertThat(notices).hasSize(4)
    }

    @Test
    fun groupObserverChangesToLocalObserversApplyOnTheNextAccess() {
        val file = IntegerRegisterFile()
        val register = file.registers[8]
        val events = mutableListOf<String>()
        val local: (RegisterAccessNotice) -> Unit = { events.add("local") }
        val group: (RegisterAccessNotice) -> Unit = {
            events.add("group")
            register.deleteObserver(local)
        }
        register.addObserver(local)
        file.addRegistersObserver(group)
        file.setValue(8, 1)
        file.getValue(8)
        Assertions.assertThat(events).containsExactly("group", "local", "group")
        file.deleteRegistersObserver(group)
        register.addObserver(local)
        file.getValue(8)
        Assertions.assertThat(events).containsExactly("group", "local", "group", "local")
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 35, Int.MAX_VALUE])
    fun invalidRegisterNumbersAreRejected(number: Int) {
        val registers = IntegerRegisterFile()
        Assertions.assertThatIllegalArgumentException()
            .isThrownBy { registers.getValue(number) }
        Assertions.assertThatIllegalArgumentException()
            .isThrownBy { registers.getValueNoNotify(number) }
        Assertions.assertThatIllegalArgumentException()
            .isThrownBy { registers.setValue(number, 42) }
    }

    @Test
    fun configurationSnapshotsDoNotShareMutableArrays() {
        val first = MemoryConfigurations.createConfiguration("Default")
        val expected = first.textBaseAddress
        first.configurationItemValues()[0] = 123
        first.configurationItemNames()[0] = "changed"
        val second = MemoryConfigurations.createConfiguration("Default")
        Assertions.assertThat(second.textBaseAddress).isEqualTo(expected)
        Assertions.assertThat(second.configurationItemNames()[0]).isNotEqualTo("changed")
        Assertions.assertThat(MemoryConfigurations.createConfiguration("").textBaseAddress)
            .isEqualTo(expected)
        Assertions.assertThat(MemoryConfigurations.createConfiguration("unknown").textBaseAddress)
            .isEqualTo(expected)
    }
}
