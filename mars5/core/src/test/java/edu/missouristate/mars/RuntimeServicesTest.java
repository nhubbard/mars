package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.*;

import edu.missouristate.mars.api.*;
import edu.missouristate.mars.core.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.hardware.memory.*;
import edu.missouristate.mars.mips.instructions.syscalls.*;
import edu.missouristate.mars.simulator.Exceptions;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeServicesTest {
  @TempDir Path directory;

  @Test
  void pendingInterruptsAndHandlersBelongOnlyToTheirSession() throws Exception {
    var source = directory.resolve("interrupt.s");
    Files.writeString(
        source,
        """
        .text
        addiu $t0,$zero,42
        li $v0,10
        syscall
        .ktext 0x80000180
        mfc0 $s0,$13
        eret
        """);
    try (var first = TestSessions.create();
        var second = TestSessions.create()) {
      first.assemble(List.of(source));
      second.assemble(List.of(source));
      first.requestExternalInterrupt(Exceptions.EXTERNAL_INTERRUPT_KEYBOARD);
      var a = first.simulator().runAsync(0, null);
      var b = second.simulator().runAsync(0, null);
      assertThat(a.get(5, TimeUnit.SECONDS).reason()).isEqualTo(StopEvent.Reason.NORMAL_EXIT);
      assertThat(b.get(5, TimeUnit.SECONDS).reason()).isEqualTo(StopEvent.Reason.NORMAL_EXIT);
      assertThat(first.registerFile.getValue(16))
          .isEqualTo(Exceptions.EXTERNAL_INTERRUPT_KEYBOARD << 2);
      assertThat(second.registerFile.getValue(16)).isZero();
      assertThat(first.registerFile.getValue(8)).isEqualTo(42);
      assertThat(first.coprocessor0.getValue(Coprocessor0State.STATUS) & 2).isZero();
    }
  }

  @Test
  void midiSyscallsDispatchNormalizedValuesToTheirSuppliedEnvironment() throws Exception {
    try (var first = TestSessions.create();
        var second = TestSessions.create()) {
      var a = new ArrayList<List<Object>>();
      var b = new ArrayList<List<Object>>();
      var left = new InstructionContext(first, midiRecorder(a));
      var right = new InstructionContext(second, midiRecorder(b));
      first.registerFile.updateRegister(4, -1);
      first.registerFile.updateRegister(5, -1);
      first.registerFile.updateRegister(6, 128);
      first.registerFile.updateRegister(7, 128);
      new SyscallMidiOut().simulate(null, left);
      second.registerFile.updateRegister(4, 64);
      second.registerFile.updateRegister(5, 10);
      second.registerFile.updateRegister(6, 3);
      second.registerFile.updateRegister(7, 20);
      new SyscallMidiOutSync().simulate(null, right);
      assertThat(a).containsExactly(List.of(60, 1000, 0, 100, false));
      assertThat(b).containsExactly(List.of(64, 10, 3, 20, true));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sparseStoragePreservesByteOrderAndCrossBlockWrites(boolean littleEndian) {
    var segment = new WordMemorySegment(2, false, () -> littleEndian);
    assertThat(segment.fetchWordOrNull(0)).isNull();
    assertThat(segment.storeWord(0, 0x12345678)).isZero();
    assertThat(segment.fetchBytes(0, 1)).isEqualTo(littleEndian ? 0x78 : 0x12);
    segment.storeBytes(4095, 2, 0xabcd);
    assertThat(segment.fetchBytes(4095, 2)).isEqualTo(0xabcd);
    assertThat(segment.fetchWordOrNull(1024)).isNotNull();
    assertThat(segment.storeWord(1024, 42)).isEqualTo(littleEndian ? 0xab : 0xab000000);
    assertThat(segment.fetchWord(1024)).isEqualTo(42);
  }

  @Test
  void textStorageKeepsAllocatedBlocksIndependent() {
    var text = new TextMemorySegment(2);
    var first = new ProgramStatement(0, 0);
    var second = new ProgramStatement(0, 4096);
    assertThat(text.read(0)).isNull();
    text.store(0, first);
    text.store(1024, second);
    assertThat(text.read(0)).isSameAs(first);
    assertThat(text.read(1024)).isSameAs(second);
    assertThat(text.read(1)).isNull();
    assertThat(text.read(2048)).isNull();
  }

  @Test
  void successfulAssemblyRetainsWarningsAndWarningPolicyIsExplicit() throws Exception {
    var source = directory.resolve("warnings.s");
    Files.writeString(source, ".text\n.unsupported\nnop\n");
    try (var session = TestSessions.create()) {
      var assembler = new SessionAssembler(session);
      var success = assembler.assemble(List.of(source), null, true, false);
      assertThat(success.succeeded()).isTrue();
      assertThat(success.errors()).hasSize(1).allMatch(AssemblyError::isWarning);
      assertThat(success.program()).isNotNull();
      assertThatThrownBy(() -> success.errors().clear())
          .isInstanceOf(UnsupportedOperationException.class);
      var failure = assembler.assemble(List.of(source), null, true, true);
      assertThat(failure.succeeded()).isFalse();
      assertThat(failure.program()).isNull();
    }
  }

  private static SyscallEnvironment midiRecorder(List<List<Object>> calls) {
    return (SyscallEnvironment)
        Proxy.newProxyInstance(
            SyscallEnvironment.class.getClassLoader(),
            new Class<?>[] {SyscallEnvironment.class},
            (proxy, method, args) -> {
              if (method.getName().equals("playTone")) {
                calls.add(List.of(args));
                return null;
              }
              throw new AssertionError("Unexpected I/O: " + method.getName());
            });
  }
}
