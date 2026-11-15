package edu.missouristate.mars.mips.hardware

import java.util.function.Consumer
import kotlin.Int
import kotlin.Long
import kotlin.RuntimeException
import kotlin.String
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class Coprocessor1StateTest {
    @Test
    fun inspectingConditionFlagsDoesNotEmitAnAccessEvent() {
        val state = Coprocessor1State()
        state.setConditionFlag(7)
        val notices = ArrayList<RegisterAccessNotice>()
        state.addRegistersObserver({ notices.add(it) })
        Assertions.assertThat(state.conditionFlagsNoNotify).isEqualTo(0x80)
        Assertions.assertThat(notices).isEmpty()
        Assertions.assertThat(state.conditionFlags).isEqualTo(0x80)
        Assertions.assertThat(notices)
            .extracting<Int?, RuntimeException>(AccessNotice::accessType)
            .containsExactly(AccessNotice.READ)
    }

    @Test
    fun allRegistersAndFlagsBelongToTheirOwnInstance() {
        val first = Coprocessor1State()
        val second = Coprocessor1State()
        for (i in 0..31) {
            Assertions.assertThat(first.updateRegister(i, i + 1)).isZero()
            Assertions.assertThat(second.getValue(i)).isZero()
            Assertions.assertThat(first.getRegister($$"$f" + i))
                .isSameAs(first.registers[i])
            Assertions.assertThat(first.getRegisterNumber($$"$f" + i)).isEqualTo(i)
        }
        first.setConditionFlags()
        Assertions.assertThat(first.conditionFlags).isEqualTo(-1)
        Assertions.assertThat(second.conditionFlags).isZero()
        second.updateRegister(31, 99)
        second.setConditionFlag(7)
        first.resetRegisters()
        for (i in 0..31) {
            Assertions.assertThat(first.getValue(i)).isZero()
        }
        Assertions.assertThat(first.conditionFlags).isZero()
        Assertions.assertThat(second.getValue(31)).isEqualTo(99)
        Assertions.assertThat(second.getConditionFlag(7)).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -0x80000000, 0x7f800000, -0x800000, 0x7fc12345, 1, 0x3fc00000])
    fun floatConversionsPreserveRawBits(bits: Int) {
        val state = Coprocessor1State()
        state.setRegisterToFloat(0, Float.fromBits(bits))
        Assertions.assertThat(state.getIntFromRegister(0)).isEqualTo(bits)
        state.setRegisterToFloat($$"$f31", state.getFloatFromRegister(0))
        Assertions.assertThat(state.getFloatFromRegister($$"$f31").toRawBits())
            .isEqualTo(bits)
        state.setRegisterToInt($$"$f30", bits)
        Assertions.assertThat(state.getIntFromRegister($$"$f30")).isEqualTo(bits)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30])
    fun registerPairsStoreTheLowWordInTheEvenRegister(number: Int) {
        val state = Coprocessor1State()
        val bits = 0x0123456789abcdefL
        state.setRegisterPairToLong(number, bits)
        Assertions.assertThat(state.getValue(number)).isEqualTo(-0x76543211)
        Assertions.assertThat(state.getValue(number + 1)).isEqualTo(0x01234567)
        Assertions.assertThat(state.getLongFromRegisterPair(number)).isEqualTo(bits)
        Assertions.assertThat(state.getLongFromRegisterPair($$"$f" + number)).isEqualTo(bits)
        state.setRegisterPairToLong($$"$f" + number, bits.inv())
        Assertions.assertThat(state.getLongFromRegisterPair(number)).isEqualTo(bits.inv())
    }

    @Test
    fun registerPairObserversSeeTheOddRegisterBeforeTheEvenRegister() {
        val state = Coprocessor1State()
        val notices = ArrayList<RegisterAccessNotice>()
        state.addRegistersObserver({ notices.add(it) })

        state.setRegisterPairToDouble(4, 1.5)
        Assertions.assertThat(state.getDoubleFromRegisterPair(4)).isEqualTo(1.5)

        Assertions.assertThat(notices)
            .extracting<String?, RuntimeException>(RegisterAccessNotice::registerName)
            .containsExactly($$"$f5", $$"$f4", $$"$f5", $$"$f4")
        Assertions.assertThat(notices)
            .extracting<Int?, RuntimeException>(AccessNotice::accessType)
            .containsExactly(
                AccessNotice.WRITE,
                AccessNotice.WRITE,
                AccessNotice.READ,
                AccessNotice.READ,
            )
    }

    @ParameterizedTest
    @ValueSource(
        longs =
            [
                0L,
                Long.MIN_VALUE,
                0x7ff0000000000000L,
                -0x10000000000000L,
                0x7ff8123456789abcL,
                1L,
                0x3ff8000000000000L,
            ],
    )
    fun doubleConversionsPreserveRawBits(bits: Long) {
        val state = Coprocessor1State()
        state.setRegisterPairToDouble(0, Double.fromBits(bits))
        Assertions.assertThat(state.getLongFromRegisterPair(0)).isEqualTo(bits)
        state.setRegisterPairToDouble($$"$f30", state.getDoubleFromRegisterPair(0))
        Assertions.assertThat(state.getDoubleFromRegisterPair($$"$f30").toRawBits())
            .isEqualTo(bits)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 31])
    fun oddRegisterPairsAreRejectedWithoutWriting(number: Int) {
        val state = Coprocessor1State()
        Assertions.assertThatExceptionOfType(InvalidRegisterAccessException::class.java)
            .isThrownBy(
                ThrowableAssert.ThrowingCallable { state.setRegisterPairToDouble(number, 1.5) },
            )
        Assertions.assertThatExceptionOfType(InvalidRegisterAccessException::class.java)
            .isThrownBy(
                ThrowableAssert.ThrowingCallable { state.setRegisterPairToLong(number, 42L) },
            )
        Assertions.assertThatExceptionOfType(InvalidRegisterAccessException::class.java)
            .isThrownBy(
                ThrowableAssert.ThrowingCallable { state.getDoubleFromRegisterPair(number) },
            )
        Assertions.assertThatExceptionOfType(InvalidRegisterAccessException::class.java)
            .isThrownBy(ThrowableAssert.ThrowingCallable { state.getLongFromRegisterPair(number) })
        Assertions.assertThat(state.getValue(number)).isZero()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7])
    fun individualConditionFlagsReturnPreviousValuesAndKeepOtherBits(flag: Int) {
        val state = Coprocessor1State()
        Assertions.assertThat(state.conditionFlagCount).isEqualTo(8)
        Assertions.assertThat(state.setConditionFlag(flag)).isZero()
        Assertions.assertThat(state.conditionFlags).isEqualTo(1 shl flag)
        Assertions.assertThat(state.setConditionFlag(flag)).isEqualTo(1)
        Assertions.assertThat(state.clearConditionFlag(flag)).isEqualTo(1)
        Assertions.assertThat(state.clearConditionFlag(flag)).isZero()
        state.setConditionFlags()
        state.clearConditionFlag(flag)
        Assertions.assertThat(state.conditionFlags).isEqualTo((1 shl flag).inv())
        state.clearConditionFlags()
        Assertions.assertThat(state.conditionFlags).isZero()
    }

    @Test
    fun unsupportedSingleRegistersAndFlagsKeepLegacyFallbacks() {
        val state = Coprocessor1State()
        state.setRegisterToInt(-1, 42)
        state.setRegisterToFloat(32, 1.5f)
        Assertions.assertThat(state.updateRegister(32, 42)).isZero()
        Assertions.assertThat(state.getIntFromRegister(-1)).isZero()
        Assertions.assertThat(state.getFloatFromRegister(32)).isZero()
        Assertions.assertThat(state.getRegisterNumber($$"$missing")).isEqualTo(-1)
        Assertions.assertThat(state.getRegisterNumber(null)).isEqualTo(-1)
        Assertions.assertThat(state.getRegisterNumber("")).isEqualTo(-1)
        Assertions.assertThat(state.getRegisterNumber($$"$f")).isEqualTo(-1)
        Assertions.assertThat(state.getRegisterNumber($$"$f32")).isEqualTo(-1)
        Assertions.assertThat(state.getRegisterNumber($$"$f010")).isEqualTo(8)
        Assertions.assertThat(state.getRegisterNumber($$"$f0x1f")).isEqualTo(31)
        Assertions.assertThat(state.getRegister($$"$f")).isNull()
        Assertions.assertThat(state.getRegister($$"$f32")).isNull()
        Assertions.assertThat(state.setConditionFlag(-1)).isZero()
        Assertions.assertThat(state.clearConditionFlag(8)).isZero()
        Assertions.assertThat(state.conditionFlags).isZero()
        state.setConditionFlag(0)
        Assertions.assertThat(state.getConditionFlag(8)).isEqualTo(1)
    }

    @Test
    fun registerAndConditionObserversStayWithTheirInstanceAndCanUnsubscribe() {
        val first = Coprocessor1State()
        val second = Coprocessor1State()
        val notices = ArrayList<RegisterAccessNotice>()
        val listener = Consumer<RegisterAccessNotice> { notices.add(it) }
        first.addRegistersObserver(listener)
        second.updateRegister(0, 42)
        second.setConditionFlag(0)
        Assertions.assertThat(notices).isEmpty()
        first.updateRegister(31, 42)
        first.setConditionFlag(7)
        Assertions.assertThat(
                notices.stream().filter { n: RegisterAccessNotice ->
                    n.accessType == AccessNotice.WRITE
                },
            )
            .extracting<String?, RuntimeException>(RegisterAccessNotice::registerName)
            .containsExactly($$"$f31", "cf")
        first.deleteRegistersObserver(listener)
        val count = notices.size
        first.updateRegister(31, 43)
        first.clearConditionFlag(7)
        Assertions.assertThat(notices).hasSize(count)
    }
}
