package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.*;

import edu.missouristate.mars.api.*;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.core.SessionAssembler;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimulatorEngineTest {
  @TempDir Path directory;

  @Test
  void stepBreakpointUndoResumeAndExitHaveOrderedEvents() throws Exception {
    try (var session = load(".text\naddiu $t0,$zero,7\naddiu $t0,$t0,2\nli $v0,10\nsyscall\n")) {
      var engine = session.simulator();
      var events = new ArrayList<>();
      engine.addListener(listener(events));
      int pc = session.registerFile.getPC();
      assertThat(engine.runAsync(1, null).get(5, TimeUnit.SECONDS).reason())
          .isEqualTo(StopEvent.Reason.STEP_LIMIT);
      assertThat(engine.state()).isEqualTo(SimulatorState.PAUSED);
      assertThat(session.registerFile.getValue(8)).isEqualTo(7);
      assertThat(events.getFirst())
          .isEqualTo(new StepEvent(pc, session.memory.getStatement(pc).getBinaryStatement(), 1));
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      assertThat(events.getLast()).isEqualTo(new StopEvent(StopEvent.Reason.BACKSTEP, 0));
      assertThat(session.registerFile.getPC()).isEqualTo(pc);
      assertThat(session.registerFile.getValue(8)).isZero();
      assertThat(engine.runAsync(0, new int[] {pc + 8}).get(5, TimeUnit.SECONDS).reason())
          .isEqualTo(StopEvent.Reason.BREAKPOINT);
      assertThat(session.registerFile.getValue(8)).isEqualTo(9);
      assertThat(engine.runAsync(0, null).get(5, TimeUnit.SECONDS).reason())
          .isEqualTo(StopEvent.Reason.NORMAL_EXIT);
      assertThat(engine.state()).isEqualTo(SimulatorState.STOPPED);
      assertThatThrownBy(engine::run).isInstanceOf(IllegalStateException.class);
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      assertThat(engine.runAsync(0, null).get(5, TimeUnit.SECONDS).reason())
          .isEqualTo(StopEvent.Reason.NORMAL_EXIT);
    }
  }

  @Test
  void reverseSourceStepUndoesOneLoopIterationAndKeepsTheOtherHistory() throws Exception {
    try (var session = load(".text\nloop: b loop\n")) {
      var engine = session.simulator();
      var events = new ArrayList<>();
      engine.addListener(listener(events));
      int entry = session.registerFile.getPC();
      engine.runAsync(4, null).get(5, TimeUnit.SECONDS);
      engine.backstepSourceAsync().get(5, TimeUnit.SECONDS);
      assertThat(session.registerFile.getPC()).isEqualTo(entry);
      assertThat(session.backStepper.canBackstep()).isTrue();
      assertThat(events.getLast()).isEqualTo(new StopEvent(StopEvent.Reason.BACKSTEP, 0));
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(events.get(events.size() - 2)).isEqualTo(
          new StepEvent(entry, session.memory.getStatementNoNotify(entry).getBinaryStatement(), 4));
    }
  }

  @Test
  void jumpWithZeroLinkRegisterDoesNotReportACall() throws Exception {
    try (var session = load(".text\njalr $zero,$t0\n")) {
      var engine = session.simulator();
      var events = new ArrayList<>();
      engine.addListener(listener(events));
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(((StepEvent) events.getFirst()).transfer()).isNull();
      assertThat(session.registerFile.getValue(0)).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void onlyTakenLinkingBranchesReportCalls(boolean delayedBranching) throws Exception {
    try (var session =
        load(
            """
            .text
            addiu $t0,$zero,-1
            bgezal $t0,target
            nop
            bgezal $zero,target
            nop
            nop
            target: nop
            """)) {
      session.settings.setDelayedBranchingEnabled(delayedBranching);
      var engine = session.simulator();
      var events = new ArrayList<>();
      engine.addListener(listener(events));
      int entry = session.registerFile.getPC();
      engine.runAsync(4, null).get(5, TimeUnit.SECONDS);
      var transfers =
          events.stream()
              .filter(StepEvent.class::isInstance)
              .map(StepEvent.class::cast)
              .map(StepEvent::transfer)
              .toList();
      int returnAddress = entry + (delayedBranching ? 20 : 16);
      assertThat(transfers).containsExactly(null, null, null, new StepEvent.Call(31, returnAddress));
      assertThat(session.registerFile.getValue(31)).isEqualTo(returnAddress);
    }
  }

  @Test
  void workerBreakpointCanStopBeforeEntryAndReevaluateAChangedAddressOnResume() throws Exception {
    try (var session = load(".text\naddiu $t0,$zero,7\naddiu $t0,$t0,2\nnop\n")) {
      var engine = session.simulator();
      int entry = session.registerFile.getPC();
      var breakpoint = new AtomicInteger(entry);
      var events = new ArrayList<>();
      engine.addListener(listener(events));
      assertThat(engine.runUntilAsync(0, pc -> pc == breakpoint.get()).get(5, TimeUnit.SECONDS))
          .isEqualTo(new StopEvent(StopEvent.Reason.BREAKPOINT, 0));
      assertThat(events).containsExactly(new StopEvent(StopEvent.Reason.BREAKPOINT, 0));
      assertThat(session.registerFile.getPC()).isEqualTo(entry);
      assertThat(session.registerFile.getValue(8)).isZero();

      var checkedEntry = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var resumed = engine.runUntilAsync(0, pc -> {
        if (pc == entry) {
          checkedEntry.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        }
        return pc == breakpoint.get();
      });
      try {
        assertThat(checkedEntry.await(5, TimeUnit.SECONDS)).isTrue();
        breakpoint.set(entry + 4);
      } finally {
        release.countDown();
      }
      assertThat(resumed.get(5, TimeUnit.SECONDS).reason()).isEqualTo(StopEvent.Reason.BREAKPOINT);
      assertThat(session.registerFile.getPC()).isEqualTo(entry + 4);
      assertThat(session.registerFile.getValue(8)).isEqualTo(7);
      assertThat(events.get(1)).isInstanceOf(StepEvent.class);
      assertThat(events.get(2)).isEqualTo(new StopEvent(StopEvent.Reason.BREAKPOINT, 0));
    }
  }

  @Test
  void stoppingWhileTheWorkerChecksABreakpointWinsWithoutExecutingTheInstruction() throws Exception {
    try (var session = load(".text\naddiu $t0,$zero,7\n")) {
      var engine = session.simulator();
      var result = engine.runUntilAsync(0, pc -> {
        engine.stop();
        return true;
      });
      assertThat(result.get(5, TimeUnit.SECONDS).reason()).isEqualTo(StopEvent.Reason.STOP_REQUEST);
      assertThat(session.registerFile.getValue(8)).isZero();
    }
  }

  @Test
  void pauseStopAndMutationRejectionOccurAtInstructionBoundaries() throws Exception {
    try (var session = load(".text\nloop: addiu $t0,$t0,1\nb loop\n")) {
      var engine = session.simulator();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blocker =
          new SimulatorListener() {
            public void onStep(StepEvent event) {
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new AssertionError("release timeout");
                }
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
            }

            public void onStop(StopEvent event) {}

            public void onError(ErrorEvent event) {}
          };
      engine.addListener(blocker);
      var run = engine.runAsync(0, null);
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        assertThatThrownBy(engine::step).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(engine::backstep).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.setMemoryConfiguration("Default"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.assemble(List.of(directory.resolve("none"))))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.getProgram().simulate(1))
            .isInstanceOf(IllegalStateException.class);
        engine.pause();
      } finally {
        release.countDown();
      }
      assertThat(run.get(5, TimeUnit.SECONDS).reason()).isEqualTo(StopEvent.Reason.PAUSE);
      assertThat(session.registerFile.getValue(8)).isEqualTo(1);
      engine.removeListener(blocker);
      var resumed = engine.runAsync(0, null);
      engine.stop();
      assertThat(resumed.get(5, TimeUnit.SECONDS).reason())
          .isEqualTo(StopEvent.Reason.STOP_REQUEST);
      assertThat(engine.state()).isEqualTo(SimulatorState.STOPPED);
    }
  }

  @Test
  void separateWorkersRunWithoutInterferenceAndCanLoadNewPrograms() throws Exception {
    try (var first = load(".text\naddiu $t0,$zero,42\nli $a0,7\nli $v0,17\nsyscall\n");
        var second = load(".text\naddiu $t0,$zero,99\n")) {
      var a = first.simulator().runAsync(0, null);
      var b = second.simulator().runAsync(0, null);
      assertThat(a.get(5, TimeUnit.SECONDS))
          .isEqualTo(new StopEvent(StopEvent.Reason.NORMAL_EXIT, 7));
      assertThat(b.get(5, TimeUnit.SECONDS).reason()).isEqualTo(StopEvent.Reason.CLIFF_TERMINATION);
      assertThat(first.registerFile.getValue(8)).isEqualTo(42);
      assertThat(second.registerFile.getValue(8)).isEqualTo(99);
      var file = directory.resolve("replacement.s");
      Files.writeString(file, ".text\naddiu $t0,$zero,12\n");
      first.assemble(List.of(file));
      assertThat(first.simulator().runAsync(0, null).get(5, TimeUnit.SECONDS).reason())
          .isEqualTo(StopEvent.Reason.CLIFF_TERMINATION);
      assertThat(first.registerFile.getValue(8)).isEqualTo(12);
    }
  }

  @Test
  void runtimeFailureEmitsErrorThenStopAndPreservesUndo() throws Exception {
    try (var session = load(".text\nli $t0,0x7fffffff\naddi $t0,$t0,1\n")) {
      var events = new ArrayList<>();
      session.simulator().addListener(listener(events));
      assertThat(session.simulator().runAsync(0, null).get(5, TimeUnit.SECONDS).reason())
          .isEqualTo(StopEvent.Reason.EXCEPTION);
      assertThat(events.get(events.size() - 2)).isInstanceOf(ErrorEvent.class);
      assertThat(events.getLast()).isEqualTo(new StopEvent(StopEvent.Reason.EXCEPTION, 0));
      assertThat(session.backStepper.empty()).isFalse();
      session.simulator().backstepAsync().get(5, TimeUnit.SECONDS);
      assertThat(session.registerFile.getValue(8)).isEqualTo(Integer.MAX_VALUE);
    }
  }

  @Test
  void assemblyApiReturnsProgramAndSourceDiagnostics() throws Exception {
    try (var session = TestSessions.create()) {
      var assembler = new SessionAssembler(session);
      var source = directory.resolve("api.s");
      Files.writeString(source, ".text\naddiu $t0,$zero,1\n");
      var success = assembler.assemble(List.of(source));
      assertThat(success.succeeded()).isTrue();
      assertThat(success.program().instructionCount()).isEqualTo(1);
      Files.writeString(source, ".text\nunknown $t0\n");
      var failure = assembler.assemble(List.of(source));
      assertThat(failure.succeeded()).isFalse();
      assertThat(failure.program()).isNull();
      assertThat(failure.errors().getFirst().file()).isEqualTo(source);
      assertThat(failure.errors().getFirst().line()).isEqualTo(2);
    }
  }

  @Test
  void closingStopsTheWorkerAtABoundaryAndRejectsNewFrontendWork() throws Exception {
    try (var session = load(".text\nloop: addiu $t0,$t0,1\nb loop\n")) {
      var engine = session.simulator();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var rejected = new AtomicBoolean();
      engine.addListener(
          new SimulatorListener() {
            public void onStep(StepEvent event) {
              try {
                session.setMemoryConfiguration("Default");
              } catch (IllegalStateException expected) {
                rejected.set(true);
              }
              entered.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
              }
            }

            public void onStop(StopEvent event) {}

            public void onError(ErrorEvent event) {}
          });
      var run = engine.runAsync(0, null);
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(rejected).isTrue();
        session.close();
        assertThatThrownBy(() -> session.setMemoryConfiguration("Default"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(engine::run).isInstanceOf(IllegalStateException.class);
      } finally {
        release.countDown();
      }
      assertThat(run.get(5, TimeUnit.SECONDS).reason()).isEqualTo(StopEvent.Reason.STOP_REQUEST);
      assertThat(session.registerFile.getValue(8)).isEqualTo(1);
    }
  }

  private MarsContext load(String source) throws Exception {
    var file = directory.resolve(UUID.randomUUID() + ".s");
    Files.writeString(file, source);
    var session = TestSessions.create();
    session.assemble(List.of(file));
    return session;
  }

  private static SimulatorListener listener(List<Object> events) {
    return new SimulatorListener() {
      public void onStep(StepEvent event) {
        events.add(event);
      }

      public void onStop(StopEvent event) {
        events.add(event);
      }

      public void onError(ErrorEvent event) {
        events.add(event);
      }
    };
  }
}
