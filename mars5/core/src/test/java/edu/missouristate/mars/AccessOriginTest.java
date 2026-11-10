package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.*;

import edu.missouristate.mars.mips.hardware.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessOriginTest {
  @TempDir Path directory;

  @Test
  void synchronousExecutionMarksInstructionFetchesAndRestoresApplicationOrigin() throws Exception {
    try (var session = TestSessions.create()) {
      var source = directory.resolve("origin.s");
      Files.writeString(source, ".text\nmain: addiu $t0,$zero,42\n");
      var program = session.assemble(List.of(source));
      var notices = new ArrayList<MemoryAccessNotice>();
      session.memory.addObserver(
          notices::add, session.memory.textBaseAddress, session.memory.textBaseAddress + 4);
      assertThat(program.simulate(1)).isFalse();
      assertThat(notices).isNotEmpty().allMatch(n ->
          n.origin() == AccessOrigin.EXECUTION && n.isInstructionFetch());
      assertThat(AccessOrigin.current()).isEqualTo(AccessOrigin.APPLICATION);
      session.memory.getStatement(session.memory.textBaseAddress);
      assertThat(notices.getLast().origin()).isEqualTo(AccessOrigin.APPLICATION);
      assertThat(notices.getLast().isInstructionFetch()).isFalse();
      assertThat(notices.getFirst().origin()).isEqualTo(AccessOrigin.EXECUTION);
    }
  }

  @Test
  void fetchFromDataIsDistinctFromReadingTheSameWord() throws Exception {
    try (var session = TestSessions.create()) {
      var memory = session.memory;
      int address = memory.dataSegmentBaseAddress;
      session.settings.setSelfModifyingCodeEnabled(true);
      memory.setWord(address, 0);
      var notices = new ArrayList<MemoryAccessNotice>();
      memory.addObserver(notices::add, address);
      AccessOrigin.duringExecution(() -> {
        memory.fetchInstruction(address);
        memory.getWord(address);
        memory.getStatementNoNotify(address);
        return null;
      });
      assertThat(notices).extracting(MemoryAccessNotice::isInstructionFetch).containsExactly(true, false);
      assertThat(notices).allMatch(n -> n.origin() == AccessOrigin.EXECUTION);
    }
  }

  @Test
  void threadNamesDoNotTurnApplicationWritesIntoInstructionWrites() throws Exception {
    try (var session = TestSessions.create()) {
      var notices = new LinkedBlockingQueue<RegisterAccessNotice>();
      session.registerFile.getRegisters()[8].addObserver(notices::add);
      var operation = new FutureTask<>(() -> session.registerFile.updateRegister(8, 42));
      Thread.ofPlatform().name("MIPS-spoof").start(operation);
      operation.get(2, TimeUnit.SECONDS);
      assertThat(notices.poll(2, TimeUnit.SECONDS).origin()).isEqualTo(AccessOrigin.APPLICATION);
    }
  }

  @Test
  void nestedBindingsAndExceptionalExitsRestoreTheOuterOrigin() throws Exception {
    assertThatThrownBy(
            () ->
                AccessOrigin.duringExecution(
                    () -> {
                      assertThat(AccessOrigin.current()).isEqualTo(AccessOrigin.EXECUTION);
                      AccessOrigin.duringExecution(
                          () -> {
                            assertThat(AccessOrigin.current()).isEqualTo(AccessOrigin.EXECUTION);
                            return null;
                          });
                      assertThat(AccessOrigin.current()).isEqualTo(AccessOrigin.EXECUTION);
                      throw new IllegalArgumentException("failed invocation");
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(AccessOrigin.current()).isEqualTo(AccessOrigin.APPLICATION);
  }
}
