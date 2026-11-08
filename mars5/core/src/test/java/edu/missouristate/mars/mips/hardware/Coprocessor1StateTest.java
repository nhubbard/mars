package edu.missouristate.mars.mips.hardware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Coprocessor1StateTest {
  @Test
  void allRegistersAndFlagsBelongToTheirOwnInstance() {
    var first = new Coprocessor1State();
    var second = new Coprocessor1State();
    for (int i = 0; i < 32; i++) {
      assertThat(first.updateRegister(i, i + 1)).isZero();
      assertThat(second.getValue(i)).isZero();
      assertThat(first.getRegister("$f" + i)).isSameAs(first.getRegisters()[i]);
      assertThat(first.getRegisterNumber("$f" + i)).isEqualTo(i);
    }
    first.setConditionFlags();
    assertThat(first.getConditionFlags()).isEqualTo(-1);
    assertThat(second.getConditionFlags()).isZero();
    second.updateRegister(31, 99);
    second.setConditionFlag(7);
    first.resetRegisters();
    for (int i = 0; i < 32; i++) {
      assertThat(first.getValue(i)).isZero();
    }
    assertThat(first.getConditionFlags()).isZero();
    assertThat(second.getValue(31)).isEqualTo(99);
    assertThat(second.getConditionFlag(7)).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 0x80000000, 0x7f800000, 0xff800000, 0x7fc12345, 1, 0x3fc00000})
  void floatConversionsPreserveRawBits(int bits) {
    var state = new Coprocessor1State();
    state.setRegisterToFloat(0, Float.intBitsToFloat(bits));
    assertThat(state.getIntFromRegister(0)).isEqualTo(bits);
    state.setRegisterToFloat("$f31", state.getFloatFromRegister(0));
    assertThat(Float.floatToRawIntBits(state.getFloatFromRegister("$f31"))).isEqualTo(bits);
    state.setRegisterToInt("$f30", bits);
    assertThat(state.getIntFromRegister("$f30")).isEqualTo(bits);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30})
  void registerPairsStoreTheLowWordInTheEvenRegister(int number) throws Exception {
    var state = new Coprocessor1State();
    long bits = 0x0123456789abcdefL;
    state.setRegisterPairToLong(number, bits);
    assertThat(state.getValue(number)).isEqualTo(0x89abcdef);
    assertThat(state.getValue(number + 1)).isEqualTo(0x01234567);
    assertThat(state.getLongFromRegisterPair(number)).isEqualTo(bits);
    assertThat(state.getLongFromRegisterPair("$f" + number)).isEqualTo(bits);
    state.setRegisterPairToLong("$f" + number, ~bits);
    assertThat(state.getLongFromRegisterPair(number)).isEqualTo(~bits);
  }

  @ParameterizedTest
  @ValueSource(
      longs = {
        0L,
        0x8000000000000000L,
        0x7ff0000000000000L,
        0xfff0000000000000L,
        0x7ff8123456789abcL,
        1L,
        0x3ff8000000000000L
      })
  void doubleConversionsPreserveRawBits(long bits) throws Exception {
    var state = new Coprocessor1State();
    state.setRegisterPairToDouble(0, Double.longBitsToDouble(bits));
    assertThat(state.getLongFromRegisterPair(0)).isEqualTo(bits);
    state.setRegisterPairToDouble("$f30", state.getDoubleFromRegisterPair(0));
    assertThat(Double.doubleToRawLongBits(state.getDoubleFromRegisterPair("$f30"))).isEqualTo(bits);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 31})
  void oddRegisterPairsAreRejectedWithoutWriting(int number) {
    var state = new Coprocessor1State();
    assertThatExceptionOfType(InvalidRegisterAccessException.class)
        .isThrownBy(() -> state.setRegisterPairToDouble(number, 1.5));
    assertThatExceptionOfType(InvalidRegisterAccessException.class)
        .isThrownBy(() -> state.setRegisterPairToLong(number, 42L));
    assertThatExceptionOfType(InvalidRegisterAccessException.class)
        .isThrownBy(() -> state.getDoubleFromRegisterPair(number));
    assertThatExceptionOfType(InvalidRegisterAccessException.class)
        .isThrownBy(() -> state.getLongFromRegisterPair(number));
    assertThat(state.getValue(number)).isZero();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void individualConditionFlagsReturnPreviousValuesAndKeepOtherBits(int flag) {
    var state = new Coprocessor1State();
    assertThat(state.getConditionFlagCount()).isEqualTo(8);
    assertThat(state.setConditionFlag(flag)).isZero();
    assertThat(state.getConditionFlags()).isEqualTo(1 << flag);
    assertThat(state.setConditionFlag(flag)).isEqualTo(1);
    assertThat(state.clearConditionFlag(flag)).isEqualTo(1);
    assertThat(state.clearConditionFlag(flag)).isZero();
    state.setConditionFlags();
    state.clearConditionFlag(flag);
    assertThat(state.getConditionFlags()).isEqualTo(~(1 << flag));
    state.clearConditionFlags();
    assertThat(state.getConditionFlags()).isZero();
  }

  @Test
  void unsupportedSingleRegistersAndFlagsKeepLegacyFallbacks() {
    var state = new Coprocessor1State();
    state.setRegisterToInt(-1, 42);
    state.setRegisterToFloat(32, 1.5f);
    assertThat(state.updateRegister(32, 42)).isZero();
    assertThat(state.getIntFromRegister(-1)).isZero();
    assertThat(state.getFloatFromRegister(32)).isZero();
    assertThat(state.getRegisterNumber("$missing")).isEqualTo(-1);
    assertThat(state.getRegister("$f32")).isNull();
    assertThat(state.setConditionFlag(-1)).isZero();
    assertThat(state.clearConditionFlag(8)).isZero();
    assertThat(state.getConditionFlags()).isZero();
    state.setConditionFlag(0);
    assertThat(state.getConditionFlag(8)).isEqualTo(1);
  }

  @Test
  void registerAndConditionObserversStayWithTheirInstanceAndCanUnsubscribe() {
    var first = new Coprocessor1State();
    var second = new Coprocessor1State();
    var notices = new ArrayList<RegisterAccessNotice>();
    Consumer<RegisterAccessNotice> listener = notices::add;
    first.addRegistersObserver(listener);
    second.updateRegister(0, 42);
    second.setConditionFlag(0);
    assertThat(notices).isEmpty();
    first.updateRegister(31, 42);
    first.setConditionFlag(7);
    assertThat(notices.stream().filter(n -> n.getAccessType() == AccessNotice.WRITE))
        .extracting(RegisterAccessNotice::getRegisterName)
        .containsExactly("$f31", "cf");
    first.deleteRegistersObserver(listener);
    int count = notices.size();
    first.updateRegister(31, 43);
    first.clearConditionFlag(7);
    assertThat(notices).hasSize(count);
  }
}
