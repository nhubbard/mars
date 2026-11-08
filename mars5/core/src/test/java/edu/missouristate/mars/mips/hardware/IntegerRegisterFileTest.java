package edu.missouristate.mars.mips.hardware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IntegerRegisterFileTest {
  @Test
  void instancesOwnAllRegistersAndResetValues() {
    var first = new IntegerRegisterFile(100, 200, 300);
    var second = new IntegerRegisterFile(400, 500, 600);
    for (int i = 0; i <= 34; i++) first.setValue(i, i + 1000);
    assertThat(first.getValue(0)).isZero();
    for (int i = 0; i <= 34; i++) {
      int expected =
          switch (i) {
            case 28 -> 400;
            case 29 -> 500;
            case 32 -> 600;
            default -> 0;
          };
      assertThat(second.getValue(i)).as("register %s", i).isEqualTo(expected);
    }
    first.reset();
    assertThat(first.getValue(28)).isEqualTo(100);
    assertThat(first.getValue(29)).isEqualTo(200);
    assertThat(first.getPC()).isEqualTo(300);
    assertThat(first.getValue(33)).isZero();
    assertThat(first.getValue(34)).isZero();
  }

  @Test
  void namesAndNumbersReferToTheSameRegister() {
    var registers = new IntegerRegisterFile();
    assertThat(registers.getNumber("$t0")).isEqualTo(8);
    assertThat(registers.getUserRegister("$8")).isSameAs(registers.getUserRegister("$t0"));
    registers.updateRegister("$t0", 42);
    assertThat(registers.getValue(8)).isEqualTo(42);
    assertThat(registers.updateRegister(8, 99)).isEqualTo(42);
    assertThat(registers.getValue(8)).isEqualTo(99);
    assertThat(registers.getNumber("$missing")).isEqualTo(-1);
    assertThat(registers.getUserRegister("$missing")).isNull();
  }

  @Test
  void programCounterSupportsInitializationIncrementAndRestore() {
    var registers = new IntegerRegisterFile(1, 2, 0x400000);
    registers.initializeProgramCounter(0x400100);
    registers.incrementPC();
    assertThat(registers.setProgramCounter(0x400200)).isEqualTo(0x400104);
    assertThat(registers.getValue(32)).isEqualTo(0x400200);
    registers.setValue(32, 0x400300);
    assertThat(registers.getPC()).isEqualTo(0x400300);
    registers.reset();
    assertThat(registers.getPC()).isEqualTo(0x400000);
  }

  @Test
  void observersOnlyReceiveTheirOwnInstancesAccessesAndCanUnsubscribe() {
    var first = new IntegerRegisterFile();
    var second = new IntegerRegisterFile();
    var notices = new ArrayList<RegisterAccessNotice>();
    Consumer<RegisterAccessNotice> listener = notices::add;
    first.addRegistersObserver(listener);
    second.setValue(8, 123);
    assertThat(notices).isEmpty();
    first.setValue(8, 42);
    first.getValue(8);
    first.setValue(33, 5);
    first.setValue(34, 6);
    assertThat(notices)
        .extracting(RegisterAccessNotice::getAccessType)
        .containsExactly(
            AccessNotice.WRITE, AccessNotice.READ, AccessNotice.WRITE, AccessNotice.WRITE);
    assertThat(notices)
        .extracting(RegisterAccessNotice::getRegisterName)
        .containsExactly("$t0", "$t0", "hi", "lo");
    first.deleteRegistersObserver(listener);
    first.setValue(8, 99);
    assertThat(notices).hasSize(4);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 35, Integer.MAX_VALUE})
  void invalidRegisterNumbersAreRejected(int number) {
    var registers = new IntegerRegisterFile();
    assertThatIllegalArgumentException().isThrownBy(() -> registers.getValue(number));
    assertThatIllegalArgumentException().isThrownBy(() -> registers.setValue(number, 42));
  }

  @Test
  void configurationSnapshotsDoNotShareMutableArrays() {
    var first =
        edu.missouristate.mars.mips.hardware.MemoryConfigurations.createConfiguration("Default");
    int expected = first.getTextBaseAddress();
    first.configurationItemValues()[0] = 123;
    first.configurationItemNames()[0] = "changed";
    var second =
        edu.missouristate.mars.mips.hardware.MemoryConfigurations.createConfiguration("Default");
    assertThat(second.getTextBaseAddress()).isEqualTo(expected);
    assertThat(second.configurationItemNames()[0]).isNotEqualTo("changed");
    assertThat(
            edu.missouristate.mars.mips.hardware.MemoryConfigurations.createConfiguration("")
                .getTextBaseAddress())
        .isEqualTo(expected);
    assertThat(
            edu.missouristate.mars.mips.hardware.MemoryConfigurations.createConfiguration("unknown")
                .getTextBaseAddress())
        .isEqualTo(expected);
  }
}
