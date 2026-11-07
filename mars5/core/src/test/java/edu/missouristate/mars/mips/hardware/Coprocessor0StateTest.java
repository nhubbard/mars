package edu.missouristate.mars.mips.hardware;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Coprocessor0StateTest {
  @ParameterizedTest
  @ValueSource(ints = {8, 12, 13, 14})
  void valuesAndResetAreIndependent(int number) {
    var first = new Coprocessor0State();
    var second = new Coprocessor0State();
    int initial = number == Coprocessor0State.STATUS ? Coprocessor0State.DEFAULT_STATUS_VALUE : 0;
    assertThat(first.getValue(number)).isEqualTo(initial);
    assertThat(first.updateRegister(number, 42)).isEqualTo(initial);
    assertThat(first.getValue(number)).isEqualTo(42);
    assertThat(second.getValue(number)).isEqualTo(initial);
    second.updateRegister(number, 123);
    first.resetRegisters();
    assertThat(first.getValue(number)).isEqualTo(initial);
    assertThat(second.getValue(number)).isEqualTo(123);
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 12, 13, 14})
  void namesResolveToThisInstancesRegister(int number) {
    var first = new Coprocessor0State();
    var second = new Coprocessor0State();
    String name = "$" + number;
    var register = first.getRegister(name);
    assertThat(first.getRegister(register.getName())).isSameAs(register);
    assertThat(first.getNumber(name)).isEqualTo(number);
    assertThat(first.getNumber(register.getName())).isEqualTo(number);
    assertThat(first.getRegisterPosition(register)).isBetween(0, 3);
    assertThat(first.getRegisterPosition(second.getRegister(name))).isEqualTo(-1);
    int initial = first.getValue(number);
    assertThat(first.updateRegister(name, 42)).isEqualTo(initial);
    assertThat(first.updateRegister(register.getName(), 99)).isEqualTo(42);
    assertThat(first.getValue(number)).isEqualTo(99);
  }

  @Test
  void unsupportedRegistersPreserveLegacyFallbacks() {
    var state = new Coprocessor0State();
    assertThat(state.getValue(0)).isZero();
    assertThat(state.updateRegister(0, 42)).isZero();
    assertThat(state.getNumber("$0")).isEqualTo(-1);
    assertThat(state.getRegister("$0")).isNull();
    assertThat(state.updateRegister("$0", 42)).isZero();
    assertThat(state.getRegisterPosition(null)).isEqualTo(-1);
  }

  @Test
  void accessObserversAreIndependentAndCanUnsubscribe() {
    var first = new Coprocessor0State();
    var second = new Coprocessor0State();
    var notices = new ArrayList<RegisterAccessNotice>();
    Consumer<RegisterAccessNotice> listener = notices::add;
    first.addRegistersObserver(listener);
    second.updateRegister(8, 100);
    second.getValue(8);
    assertThat(notices).isEmpty();
    first.updateRegister(8, 42);
    first.getValue(8);
    assertThat(notices)
        .extracting(RegisterAccessNotice::getAccessType)
        .containsExactly(AccessNotice.WRITE, AccessNotice.READ);
    assertThat(notices)
        .extracting(RegisterAccessNotice::getRegisterName)
        .containsOnly("$8 (vaddr)");
    first.deleteRegistersObserver(listener);
    first.updateRegister(8, 43);
    assertThat(notices).hasSize(2);
  }
}
