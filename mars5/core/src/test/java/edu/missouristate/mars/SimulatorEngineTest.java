package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.*;

import edu.missouristate.mars.api.*;
import edu.missouristate.mars.api.StepEvent.BranchOutcome;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.core.SessionAssembler;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.simulator.BackStepper;
import edu.missouristate.mars.simulator.Exceptions;
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
      assertThat(events.get(events.size() - 2)).isEqualTo(0L);
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
  void instructionBudgetSurvivesBreakpointsAndReverseExecution() throws Exception {
    try (var session = load("loop: addiu $t0,$t0,1\nj loop\n")) {
      var engine = session.simulator();
      assertThatThrownBy(() -> engine.setInstructionLimit(-1))
          .isInstanceOf(IllegalArgumentException.class);
      engine.setInstructionLimit(3);
      int entry = session.registerFile.getPC();
      assertThat(engine.runAsync(1, null).get(5, TimeUnit.SECONDS).reason())
          .isEqualTo(StopEvent.Reason.STEP_LIMIT);
      assertThatThrownBy(() -> engine.setInstructionLimit(4))
          .isInstanceOf(IllegalStateException.class);
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      assertThat(session.registerFile.getValue(8)).isZero();
      assertThat(engine.runUntilAsync(0, pc -> pc == entry + 4).get(5, TimeUnit.SECONDS).reason())
          .isEqualTo(StopEvent.Reason.BREAKPOINT);
      assertThat(engine.runAsync(1, null).get(5, TimeUnit.SECONDS).reason())
          .isEqualTo(StopEvent.Reason.INSTRUCTION_LIMIT);
      assertThat(session.registerFile.getPC()).isEqualTo(entry);
      assertThat(session.registerFile.getValue(8)).isEqualTo(1);
      assertThat(engine.state()).isEqualTo(SimulatorState.STOPPED);
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      assertThatThrownBy(engine::run).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void naturalExitOnTheFinalAllowedInstructionRetainsItsExitStatus() throws Exception {
    try (var session = load("addiu $v0,$zero,17\naddiu $a0,$zero,7\nsyscall\n")) {
      var engine = session.simulator();
      engine.setInstructionLimit(3);
      assertThat(engine.runAsync(0, null).get(5, TimeUnit.SECONDS))
          .isEqualTo(new StopEvent(StopEvent.Reason.NORMAL_EXIT, 7));
    }
  }

  @Test
  void changingRunSpeedReleasesAnInstructionAlreadyWaitingForItsTick() throws Exception {
    try (var session = load("loop: addiu $t0,$t0,1\nj loop\n")) {
      var engine = session.simulator();
      var waiting = new CountDownLatch(1);
      var unlimited = new AtomicBoolean();
      engine.setRunSpeedSource(
          () -> {
            if (unlimited.get()) return 0;
            waiting.countDown();
            return 0.05;
          });
      var completion = engine.runAsync(3, null);
      assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(completion).isNotDone();
      unlimited.set(true);
      assertThat(completion.get(2, TimeUnit.SECONDS).reason()).isEqualTo(StopEvent.Reason.STEP_LIMIT);
      assertThat(session.registerFile.getValue(8)).isEqualTo(2);
    }
  }

  @Test
  void deviceCheckpointsShareTheInstructionFrameAndExpireWithIt() throws Exception {
    try (var session = load("nop\n")) {
      int pc = session.registerFile.getPC();
      var statement = session.memory.getStatementNoNotify(pc);
      var history = new BackStepper(session.memory, session.registerFile, session.coprocessor0,
          session.coprocessor1, session.delayedBranch, 1);
      var restored = new ArrayList<Integer>();
      var value = new AtomicInteger();
      try (var first = history.addInstructionCheckpoint(() -> {
             int previous = value.get();
             return () -> {
               assertThat(session.registerFile.getPC()).isEqualTo(pc);
               restored.add(previous);
             };
           });
           var skipped = history.addInstructionCheckpoint(() -> null);
           var second = history.addInstructionCheckpoint(() -> () -> restored.add(-1))) {
        history.beginInstruction(pc, statement);
        history.endInstruction();
        value.set(7);
        history.beginInstruction(pc, statement);
        history.endInstruction();
        session.registerFile.setProgramCounter(pc + 4);
        history.backStep();
        assertThat(history.canBackstep()).isFalse();
        assertThat(restored).containsExactly(7, -1);

        history.beginInstruction(pc, statement);
        history.addRegisterFileRestore(8, 0);
        history.addRegisterFileRestore(9, 0);
        history.endInstruction();
        assertThat(history.canBackstep()).isFalse();
        assertThat(restored).containsExactly(7, -1);
      }
      history.beginInstruction(pc, statement);
      history.endInstruction();
      history.backStep();
      assertThat(restored).containsExactly(7, -1);
    }
  }

  @Test
  void stoppedRegisterEditStartsNewHistoryOnlyWhenTheValueChanges() throws Exception {
    try (var session = load(".text\naddiu $t0,$zero,7\nnop\n")) {
      var engine = session.simulator();
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(session.editIntegerRegister(8, 7)).isFalse();
      assertThat(session.backStepper.canBackstep()).isTrue();
      assertThatThrownBy(() -> session.editIntegerRegister(0, 1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> session.editIntegerRegister(32, 1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(session.backStepper.canBackstep()).isTrue();
      assertThat(session.editIntegerRegister(8, 42)).isTrue();
      assertThat(session.registerFile.getValueNoNotify(8)).isEqualTo(42);
      assertThat(session.backStepper.canBackstep()).isFalse();
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      assertThat(session.registerFile.getValueNoNotify(8)).isEqualTo(42);
    }
  }

  @Test
  void stoppedMemoryEditStartsNewHistoryOnlyAfterAValidChange() throws Exception {
    try (var session = load(".data\ncell: .word 7\n.text\nli $t0,9\nsw $t0,cell\nnop\n")) {
      var engine = session.simulator();
      var memory = session.memory;
      int address = memory.dataBaseAddress;
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(session.editMemoryWord(address, 7)).isFalse();
      assertThatThrownBy(() -> session.editMemoryWord(address + 1, 8))
          .isInstanceOf(AddressErrorException.class);
      assertThatThrownBy(() -> session.editMemoryWord(0x20000000, 8))
          .isInstanceOf(AddressErrorException.class);
      int originalInstruction = memory.inspectWord(memory.textBaseAddress);
      assertThatThrownBy(() -> session.editMemoryWord(memory.textBaseAddress, originalInstruction))
          .isInstanceOf(AddressErrorException.class);
      assertThat(session.backStepper.canBackstep()).isTrue();
      assertThat(session.editMemoryWord(address, 42)).isTrue();
      assertThat(session.backStepper.canBackstep()).isFalse();
      engine.runAsync(2, null).get(5, TimeUnit.SECONDS);
      assertThat(memory.inspectWord(address)).isEqualTo(9);
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      assertThat(memory.inspectWord(address)).isEqualTo(42);
    }
  }

  @Test
  void stoppedCoprocessor0EditBecomesTheBaselineForReversingInstructionWrites() throws Exception {
    try (var session = load(".text\naddiu $t0,$zero,7\nmtc0 $t0,$14\nnop\n")) {
      var engine = session.simulator();
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(session.editCoprocessor0Register(Coprocessor0State.EPC, 0)).isFalse();
      assertThatThrownBy(() -> session.editCoprocessor0Register(9, 1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(session.backStepper.canBackstep()).isTrue();

      assertThat(session.editCoprocessor0Register(Coprocessor0State.EPC, 42)).isTrue();
      assertThat(session.coprocessor0.getValue(Coprocessor0State.EPC)).isEqualTo(42);
      assertThat(session.backStepper.canBackstep()).isFalse();
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(session.coprocessor0.getValue(Coprocessor0State.EPC)).isEqualTo(7);
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      assertThat(session.coprocessor0.getValue(Coprocessor0State.EPC)).isEqualTo(42);
    }
  }

  @Test
  void stoppedCoprocessor1EditsPreserveRawBitsAndBecomeTheReverseBaseline() throws Exception {
    try (var session =
        load(".text\nnop\nmov.d $f30,$f2\nc.eq.d 7,$f0,$f2\nnop\n")) {
      var engine = session.simulator();
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(session.editCoprocessor1Register(2, 0)).isFalse();
      assertThat(session.editCoprocessor1Pair(30, 0)).isFalse();
      assertThat(session.editCoprocessor1ConditionFlags(0)).isFalse();
      assertThatThrownBy(() -> session.editCoprocessor1Register(32, 1))
          .isInstanceOf(IndexOutOfBoundsException.class);
      assertThatThrownBy(() -> session.editCoprocessor1Pair(31, 1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> session.editCoprocessor1ConditionFlags(256))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(session.backStepper.canBackstep()).isTrue();

      long editedPair = 0xfff8000000000123L;
      assertThat(session.editCoprocessor1Register(2, 0x80000000)).isTrue();
      assertThat(session.editCoprocessor1Pair(30, editedPair)).isTrue();
      assertThat(session.editCoprocessor1ConditionFlags(0x81)).isTrue();
      assertThat(session.backStepper.canBackstep()).isFalse();
      assertThat(session.coprocessor1.getRegisters()[30].getValueNoNotify()).isEqualTo(0x123);
      assertThat(session.coprocessor1.getRegisters()[31].getValueNoNotify()).isEqualTo(0xfff80000);
      engine.runAsync(2, null).get(5, TimeUnit.SECONDS);
      assertThat(session.coprocessor1.getLongFromRegisterPair(30)).isEqualTo(0x80000000L);
      assertThat(session.coprocessor1.getConditionFlagsNoNotify()).isEqualTo(1);
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      assertThat(session.coprocessor1.getConditionFlagsNoNotify()).isEqualTo(0x81);
      assertThat(session.coprocessor1.getLongFromRegisterPair(30)).isEqualTo(editedPair);
      assertThat(session.backStepper.canBackstep()).isFalse();
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
          new StepEvent(
              entry, session.memory.getStatementNoNotify(entry).getBinaryStatement(), 4,
              null, BranchOutcome.TAKEN));
    }
  }

  @Test
  void exceptionReturnReportsItsTransferAfterRestoringTheProgramCounter() throws Exception {
    try (var session = load(".text\neret\nnop\n")) {
      var engine = session.simulator();
      var events = new ArrayList<>();
      engine.addListener(listener(events));
      int target = session.registerFile.getPC() + 4;
      session.editCoprocessor0Register(Coprocessor0State.EPC, target);
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(((StepEvent) events.getFirst()).transfer()).isEqualTo(StepEvent.ExceptionReturn.INSTANCE);
      assertThat(session.registerFile.getPC()).isEqualTo(target);
    }
  }

  @Test
  void jumpWithZeroLinkRegisterDoesNotReportACall() throws Exception {
    try (var session = load(".text\njalr $zero,$t0\n")) {
      var engine = session.simulator();
      var events = new ArrayList<>();
      engine.addListener(listener(events));
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(((StepEvent) events.getFirst()).transfer()).isEqualTo(new StepEvent.Jump(0));
      assertThat(session.registerFile.getValue(0)).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nonLinkingJumpReportsArrivalAndUndoRestoresTheDelayedTransfer(boolean delayed)
      throws Exception {
    try (var session = load(".text\nj target\nnop\ntarget: nop\n")) {
      session.settings.setDelayedBranchingEnabled(delayed);
      var engine = session.simulator();
      var events = new ArrayList<>();
      engine.addListener(listener(events));
      int target = session.registerFile.getPC() + 8;
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      var transfer = new StepEvent.Jump(target);
      if (delayed) {
        assertThat(((StepEvent) events.getFirst()).transfer()).isNull();
        engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      }
      assertThat(((StepEvent) events.get(events.size() - 2)).transfer()).isEqualTo(transfer);
      assertThat(session.registerFile.getPC()).isEqualTo(target);
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(((StepEvent) events.get(events.size() - 2)).transfer()).isEqualTo(transfer);
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
      var steps =
          events.stream()
              .filter(StepEvent.class::isInstance)
              .map(StepEvent.class::cast)
              .toList();
      int returnAddress = entry + (delayedBranching ? 20 : 16);
      assertThat(steps).extracting(StepEvent::transfer)
          .containsExactly(null, null, null, new StepEvent.Call(31, returnAddress));
      assertThat(steps).extracting(StepEvent::branchOutcome)
          .containsExactly(null, BranchOutcome.NOT_TAKEN, null, BranchOutcome.TAKEN);
      assertThat(session.registerFile.getValue(31)).isEqualTo(returnAddress);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void branchToTheNextInstructionReportsTakenEvenWhenTheProgramCounterLooksSequential(boolean delayed)
      throws Exception {
    try (var session = load(".text\nbeq $zero,$zero,next\nnext: nop\n")) {
      session.settings.setDelayedBranchingEnabled(delayed);
      var engine = session.simulator();
      var events = new ArrayList<>();
      engine.addListener(listener(events));
      int entry = session.registerFile.getPC();
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      assertThat(session.registerFile.getPC()).isEqualTo(entry + 4);
      engine.backstepAsync().get(5, TimeUnit.SECONDS);
      engine.runAsync(2, null).get(5, TimeUnit.SECONDS);
      assertThat(events.stream().filter(StepEvent.class::isInstance).map(StepEvent.class::cast))
          .extracting(StepEvent::branchOutcome)
          .containsExactly(BranchOutcome.TAKEN, BranchOutcome.TAKEN, null);
    }
  }

  @Test
  void interruptBeforeABranchDoesNotReportAnOutcome() throws Exception {
    try (var session = load(".text\nloop: beq $zero,$zero,loop\n.ktext 0x80000180\nnop\n")) {
      var engine = session.simulator();
      var events = new ArrayList<>();
      engine.addListener(listener(events));
      session.requestExternalInterrupt(Exceptions.EXTERNAL_INTERRUPT_KEYBOARD);
      engine.runAsync(1, null).get(5, TimeUnit.SECONDS);
      var event = (StepEvent) events.getFirst();
      assertThat(event.transfer()).isEqualTo(StepEvent.ExceptionEntry.INSTANCE);
      assertThat(event.branchOutcome()).isNull();
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
        assertThatThrownBy(() -> session.editIntegerRegister(8, 42))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.editMemoryWord(session.memory.dataBaseAddress, 42))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.editCoprocessor0Register(Coprocessor0State.EPC, 42))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.editCoprocessor1Register(0, 42))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.editCoprocessor1Pair(0, 42))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.editCoprocessor1ConditionFlags(1))
            .isInstanceOf(IllegalStateException.class);
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
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
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

      public void onBackstep(long stepCount) {
        events.add(stepCount);
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
