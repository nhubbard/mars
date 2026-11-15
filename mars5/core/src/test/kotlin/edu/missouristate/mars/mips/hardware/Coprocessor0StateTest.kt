package edu.missouristate.mars.mips.hardware

import java.util.Objects
import java.util.function.Consumer
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class Coprocessor0StateTest {
    @ParameterizedTest
    @ValueSource(ints = [8, 12, 13, 14])
    fun valuesAndResetAreIndependent(number: Int) {
        val first = Coprocessor0State()
        val second = Coprocessor0State()
        val initial =
            if (number == Coprocessor0State.STATUS) Coprocessor0State.DEFAULT_STATUS_VALUE else 0
        Assertions.assertThat(first.getValue(number)).isEqualTo(initial)
        Assertions.assertThat(first.updateRegister(number, 42)).isEqualTo(initial)
        Assertions.assertThat(first.getValue(number)).isEqualTo(42)
        Assertions.assertThat(second.getValue(number)).isEqualTo(initial)
        second.updateRegister(number, 123)
        first.resetRegisters()
        Assertions.assertThat(first.getValue(number)).isEqualTo(initial)
        Assertions.assertThat(second.getValue(number)).isEqualTo(123)
    }

    @ParameterizedTest
    @ValueSource(ints = [8, 12, 13, 14])
    fun namesResolveToThisInstancesRegister(number: Int) {
        val first = Coprocessor0State()
        val second = Coprocessor0State()
        val name = "$" + number
        val register = Objects.requireNonNull<Register>(first.getRegister(name))
        Assertions.assertThat<Register?>(first.getRegister(register.name)).isSameAs(register)
        Assertions.assertThat(first.getNumber(name)).isEqualTo(number)
        Assertions.assertThat(first.getNumber(register.name)).isEqualTo(number)
        Assertions.assertThat(first.getRegisterPosition(register)).isBetween(0, 3)
        Assertions.assertThat(first.getRegisterPosition(second.getRegister(name))).isEqualTo(-1)
        val initial = first.getValue(number)
        Assertions.assertThat(first.updateRegister(name, 42)).isEqualTo(initial)
        Assertions.assertThat(first.updateRegister(register.name, 99)).isEqualTo(42)
        Assertions.assertThat(first.getValue(number)).isEqualTo(99)
    }

    @Test
    fun unsupportedRegistersPreserveLegacyFallbacks() {
        val state = Coprocessor0State()
        Assertions.assertThat(state.getValue(0)).isZero()
        Assertions.assertThat(state.updateRegister(0, 42)).isZero()
        Assertions.assertThat(state.getNumber("$0")).isEqualTo(-1)
        Assertions.assertThat<Register?>(state.getRegister("$0")).isNull()
        Assertions.assertThat(state.updateRegister("$0", 42)).isZero()
        Assertions.assertThat(state.getRegisterPosition(null)).isEqualTo(-1)
    }

    @Test
    fun accessObserversAreIndependentAndCanUnsubscribe() {
        val first = Coprocessor0State()
        val second = Coprocessor0State()
        val notices = ArrayList<RegisterAccessNotice>()
        val listener = Consumer<RegisterAccessNotice> { notices.add(it) }
        first.addRegistersObserver(listener)
        second.updateRegister(8, 100)
        second.getValue(8)
        Assertions.assertThat<RegisterAccessNotice>(notices).isEmpty()
        first.updateRegister(8, 42)
        first.getValue(8)
        Assertions.assertThat<RegisterAccessNotice>(notices)
            .extracting<Int?, RuntimeException>(AccessNotice::accessType)
            .containsExactly(AccessNotice.WRITE, AccessNotice.READ)
        Assertions.assertThat<RegisterAccessNotice>(notices)
            .extracting<String?, RuntimeException>(RegisterAccessNotice::registerName)
            .containsOnly("$8 (vaddr)")
        first.deleteRegistersObserver(listener)
        first.updateRegister(8, 43)
        Assertions.assertThat<RegisterAccessNotice>(notices).hasSize(2)
    }
}
