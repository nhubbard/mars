package edu.missouristate.mars

import edu.missouristate.mars.api.AssemblyResult
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.Program
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.SimulatorState
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.BranchOutcome
import edu.missouristate.mars.api.StepEvent.ExceptionEntry
import edu.missouristate.mars.api.StepEvent.ExceptionReturn
import edu.missouristate.mars.api.StepEvent.Jump
import edu.missouristate.mars.api.StepEvent.Transfer
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.core.SessionAssembler
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.simulator.BackStepper
import edu.missouristate.mars.simulator.Exceptions.EXTERNAL_INTERRUPT_KEYBOARD
import edu.missouristate.mars.simulator.SimulatorEngine
import java.nio.file.Files
import java.nio.file.Path
import java.util.Objects
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.DoubleSupplier
import java.util.function.IntPredicate
import java.util.function.Supplier
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class SimulatorEngineTest {
    @TempDir lateinit var directory: Path

    @Test
    fun stepBreakpointUndoResumeAndExitHaveOrderedEvents() {
        load($$".text\naddiu $t0,$zero,7\naddiu $t0,$t0,2\nli $v0,10\nsyscall\n").use { session
            ->
            val engine: SimulatorEngine = session.simulator()
            val events = ArrayList<Any?>()
            engine.addListener(listener(events))
            val pc: Int = session.registerFile.getPC()
            Assertions.assertThat<StopEvent.Reason?>(
                    engine.runAsync(1, null).get(5, TimeUnit.SECONDS).reason,
                )
                .isEqualTo(StopEvent.Reason.STEP_LIMIT)
            Assertions.assertThat<SimulatorState?>(engine.state()).isEqualTo(SimulatorState.PAUSED)
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(7)
            Assertions.assertThat<Any?>(events.first())
                .isEqualTo(
                    StepEvent(
                        pc,
                        requireNotNull(session.memory.getStatement(pc)).binaryStatement,
                        1,
                    ),
                )
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            Assertions.assertThat<Any?>(events.get(events.size - 2)).isEqualTo(0L)
            Assertions.assertThat<Any?>(events.last())
                .isEqualTo(StopEvent(StopEvent.Reason.BACKSTEP, 0))
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(pc)
            Assertions.assertThat(session.registerFile.getValue(8)).isZero()
            Assertions.assertThat<StopEvent.Reason?>(
                    engine.runAsync(0, intArrayOf(pc + 8)).get(5, TimeUnit.SECONDS).reason,
                )
                .isEqualTo(StopEvent.Reason.BREAKPOINT)
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(9)
            Assertions.assertThat<StopEvent.Reason?>(
                    engine.runAsync(0, null).get(5, TimeUnit.SECONDS).reason,
                )
                .isEqualTo(StopEvent.Reason.NORMAL_EXIT)
            Assertions.assertThat<SimulatorState?>(engine.state()).isEqualTo(SimulatorState.STOPPED)
            Assertions.assertThatThrownBy(ThrowableAssert.ThrowingCallable { engine.run() })
                .isInstanceOf(IllegalStateException::class.java)
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            Assertions.assertThat<StopEvent.Reason?>(
                    engine.runAsync(0, null).get(5, TimeUnit.SECONDS).reason,
                )
                .isEqualTo(StopEvent.Reason.NORMAL_EXIT)
        }
    }

    @Test
    fun instructionBudgetSurvivesBreakpointsAndReverseExecution() {
        load($$"loop: addiu $t0,$t0,1\nj loop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { engine.setInstructionLimit(-1) },
                )
                .isInstanceOf(IllegalArgumentException::class.java)
            engine.setInstructionLimit(3)
            val entry: Int = session.registerFile.getPC()
            Assertions.assertThat<StopEvent.Reason?>(
                    engine.runAsync(1, null).get(5, TimeUnit.SECONDS).reason,
                )
                .isEqualTo(StopEvent.Reason.STEP_LIMIT)
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { engine.setInstructionLimit(4) },
                )
                .isInstanceOf(IllegalStateException::class.java)
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.registerFile.getValue(8)).isZero()
            Assertions.assertThat<StopEvent.Reason?>(
                    engine
                        .runUntilAsync(
                            0,
                            IntPredicate { pc: Int -> pc == entry + 4 },
                        )
                        .get(5, TimeUnit.SECONDS)
                        .reason,
                )
                .isEqualTo(StopEvent.Reason.BREAKPOINT)
            Assertions.assertThat<StopEvent.Reason?>(
                    engine.runAsync(1, null).get(5, TimeUnit.SECONDS).reason,
                )
                .isEqualTo(StopEvent.Reason.INSTRUCTION_LIMIT)
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(entry)
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(1)
            Assertions.assertThat<SimulatorState?>(engine.state()).isEqualTo(SimulatorState.STOPPED)
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            Assertions.assertThatThrownBy(ThrowableAssert.ThrowingCallable { engine.run() })
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun naturalExitOnTheFinalAllowedInstructionRetainsItsExitStatus() {
        load($$"addiu $v0,$zero,17\naddiu $a0,$zero,7\nsyscall\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            engine.setInstructionLimit(3)
            Assertions.assertThat<StopEvent>(engine.runAsync(0, null).get(5, TimeUnit.SECONDS))
                .isEqualTo(StopEvent(StopEvent.Reason.NORMAL_EXIT, 7))
        }
    }

    @Test
    fun changingRunSpeedReleasesAnInstructionAlreadyWaitingForItsTick() {
        load($$"loop: addiu $t0,$t0,1\nj loop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            val waiting = CountDownLatch(1)
            val unlimited = AtomicBoolean()
            engine.setRunSpeedSource(
                DoubleSupplier {
                    if (unlimited.get()) return@DoubleSupplier 0.0
                    waiting.countDown()
                    0.05
                },
            )
            val completion: CompletableFuture<StopEvent> = engine.runAsync(3, null)
            Assertions.assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue()
            Assertions.assertThat<StopEvent>(completion).isNotDone()
            unlimited.set(true)
            Assertions.assertThat<StopEvent.Reason?>(completion.get(2, TimeUnit.SECONDS)!!.reason)
                .isEqualTo(StopEvent.Reason.STEP_LIMIT)
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(2)
        }
    }

    @Test
    fun deviceCheckpointsShareTheInstructionFrameAndExpireWithIt() {
        load("nop\n").use { session ->
            val pc: Int = session.registerFile.getPC()
            val statement: ProgramStatement? = session.memory.getStatementNoNotify(pc)
            val history =
                BackStepper(
                    session.memory,
                    session.registerFile,
                    session.coprocessor0,
                    session.coprocessor1,
                    session.delayedBranch,
                    1,
                )
            val restored = ArrayList<Int?>()
            val value = AtomicInteger()
            history
                .addInstructionCheckpoint(
                    Supplier {
                        val previous = value.get()
                        Runnable {
                            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(pc)
                            restored.add(previous)
                        }
                    },
                )
                .use { `_` ->
                    history.addInstructionCheckpoint(Supplier { null }).use { `_` ->
                        history
                            .addInstructionCheckpoint(Supplier { Runnable { restored.add(-1) } })
                            .use { `_` ->
                                history.beginInstruction(pc, statement)
                                history.endInstruction()
                                value.set(7)
                                history.beginInstruction(pc, statement)
                                history.endInstruction()
                                session.registerFile.setProgramCounter(pc + 4)
                                history.backStep()
                                Assertions.assertThat(history.canBackstep()).isFalse()
                                Assertions.assertThat<Int?>(restored).containsExactly(7, -1)

                                history.beginInstruction(pc, statement)
                                history.addRegisterFileRestore(8, 0)
                                history.addRegisterFileRestore(9, 0)
                                history.endInstruction()
                                Assertions.assertThat(history.canBackstep()).isFalse()
                                Assertions.assertThat<Int?>(restored).containsExactly(7, -1)
                            }
                    }
                }
            history.beginInstruction(pc, statement)
            history.endInstruction()
            history.backStep()
            Assertions.assertThat<Int?>(restored).containsExactly(7, -1)
        }
    }

    @Test
    fun stoppedRegisterEditStartsNewHistoryOnlyWhenTheValueChanges() {
        load($$".text\naddiu $t0,$zero,7\nnop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.editIntegerRegister(8, 7)).isFalse()
            Assertions.assertThat(session.backStepper.canBackstep()).isTrue()
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { session.editIntegerRegister(0, 1) },
                )
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { session.editIntegerRegister(32, 1) },
                )
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThat(session.backStepper.canBackstep()).isTrue()
            Assertions.assertThat(session.editIntegerRegister(8, 42)).isTrue()
            Assertions.assertThat(session.registerFile.getValueNoNotify(8)).isEqualTo(42)
            Assertions.assertThat(session.backStepper.canBackstep()).isFalse()
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.registerFile.getValueNoNotify(8)).isEqualTo(42)
        }
    }

    @Test
    fun stoppedMemoryEditStartsNewHistoryOnlyAfterAValidChange() {
        load($$".data\ncell: .word 7\n.text\nli $t0,9\nsw $t0,cell\nnop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            val memory: MemoryState = session.memory
            val address = memory.dataBaseAddress
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.editMemoryWord(address, 7)).isFalse()
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { session.editMemoryWord(address + 1, 8) },
                )
                .isInstanceOf(AddressErrorException::class.java)
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { session.editMemoryWord(0x20000000, 8) },
                )
                .isInstanceOf(AddressErrorException::class.java)
            val originalInstruction: Int =
                Objects.requireNonNull<Int?>(memory.inspectWord(memory.textBaseAddress))!!
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable {
                        session.editMemoryWord(
                            memory.textBaseAddress,
                            originalInstruction,
                        )
                    },
                )
                .isInstanceOf(AddressErrorException::class.java)
            Assertions.assertThat(session.backStepper.canBackstep()).isTrue()
            Assertions.assertThat(session.editMemoryWord(address, 42)).isTrue()
            Assertions.assertThat(session.backStepper.canBackstep()).isFalse()
            engine.runAsync(2, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat(memory.inspectWord(address)).isEqualTo(9)
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            Assertions.assertThat(memory.inspectWord(address)).isEqualTo(42)
        }
    }

    @Test
    fun stoppedCoprocessor0EditBecomesTheBaselineForReversingInstructionWrites() {
        load($$".text\naddiu $t0,$zero,7\nmtc0 $t0,$14\nnop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.editCoprocessor0Register(Coprocessor0State.EPC, 0))
                .isFalse()
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { session.editCoprocessor0Register(9, 1) },
                )
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThat(session.backStepper.canBackstep()).isTrue()

            Assertions.assertThat(session.editCoprocessor0Register(Coprocessor0State.EPC, 42))
                .isTrue()
            Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.EPC))
                .isEqualTo(42)
            Assertions.assertThat(session.backStepper.canBackstep()).isFalse()
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.EPC)).isEqualTo(7)
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.EPC))
                .isEqualTo(42)
        }
    }

    @Test
    fun stoppedCoprocessor1EditsPreserveRawBitsAndBecomeTheReverseBaseline() {
        load($$".text\nnop\nmov.d $f30,$f2\nc.eq.d 7,$f0,$f2\nnop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.editCoprocessor1Register(2, 0)).isFalse()
            Assertions.assertThat(session.editCoprocessor1Pair(30, 0)).isFalse()
            Assertions.assertThat(session.editCoprocessor1ConditionFlags(0)).isFalse()
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { session.editCoprocessor1Register(32, 1) },
                )
                .isInstanceOf(IndexOutOfBoundsException::class.java)
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { session.editCoprocessor1Pair(31, 1) },
                )
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable {
                        session.editCoprocessor1ConditionFlags(256)
                    },
                )
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThat(session.backStepper.canBackstep()).isTrue()

            val editedPair = -0x7fffffffffeddL
            Assertions.assertThat(session.editCoprocessor1Register(2, -0x80000000)).isTrue()
            Assertions.assertThat(session.editCoprocessor1Pair(30, editedPair)).isTrue()
            Assertions.assertThat(session.editCoprocessor1ConditionFlags(0x81)).isTrue()
            Assertions.assertThat(session.backStepper.canBackstep()).isFalse()
            Assertions.assertThat(session.coprocessor1.registers[30].valueNoNotify).isEqualTo(0x123)
            Assertions.assertThat(session.coprocessor1.registers[31].valueNoNotify)
                .isEqualTo(-0x80000)
            engine.runAsync(2, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.coprocessor1.getLongFromRegisterPair(30))
                .isEqualTo(0x80000000L)
            Assertions.assertThat(session.coprocessor1.conditionFlagsNoNotify).isEqualTo(1)
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.coprocessor1.conditionFlagsNoNotify).isEqualTo(0x81)
            Assertions.assertThat(session.coprocessor1.getLongFromRegisterPair(30))
                .isEqualTo(editedPair)
            Assertions.assertThat(session.backStepper.canBackstep()).isFalse()
        }
    }

    @Test
    fun reverseSourceStepUndoesOneLoopIterationAndKeepsTheOtherHistory() {
        load(".text\nloop: b loop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            val events = ArrayList<Any?>()
            engine.addListener(listener(events))
            val entry: Int = session.registerFile.getPC()
            engine.runAsync(4, null).get(5, TimeUnit.SECONDS)
            engine.backstepSourceAsync().get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(entry)
            Assertions.assertThat(session.backStepper.canBackstep()).isTrue()
            Assertions.assertThat<Any?>(events.last())
                .isEqualTo(StopEvent(StopEvent.Reason.BACKSTEP, 0))
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat<Any?>(events.get(events.size - 2))
                .isEqualTo(
                    StepEvent(
                        entry,
                        requireNotNull(session.memory.getStatementNoNotify(entry)).binaryStatement,
                        4,
                        null,
                        BranchOutcome.TAKEN,
                    ),
                )
        }
    }

    @Test
    fun exceptionReturnReportsItsTransferAfterRestoringTheProgramCounter() {
        load(".text\neret\nnop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            val events = ArrayList<Any?>()
            engine.addListener(listener(events))
            val target: Int = session.registerFile.getPC() + 4
            session.editCoprocessor0Register(Coprocessor0State.EPC, target)
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat<Transfer?>((events.first() as StepEvent).transfer)
                .isEqualTo(ExceptionReturn.INSTANCE)
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(target)
        }
    }

    @Test
    fun jumpWithZeroLinkRegisterDoesNotReportACall() {
        load($$".text\njalr $zero,$t0\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            val events = ArrayList<Any?>()
            engine.addListener(listener(events))
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat<Transfer?>((events.first() as StepEvent).transfer)
                .isEqualTo(Jump(0))
            Assertions.assertThat(session.registerFile.getValue(0)).isZero()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun nonLinkingJumpReportsArrivalAndUndoRestoresTheDelayedTransfer(delayed: Boolean) {
        load(".text\nj target\nnop\ntarget: nop\n").use { session ->
            session.settings.setDelayedBranchingEnabled(delayed)
            val engine: SimulatorEngine = session.simulator()
            val events = ArrayList<Any?>()
            engine.addListener(listener(events))
            val target: Int = session.registerFile.getPC() + 8
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            val transfer = Jump(target)
            if (delayed) {
                Assertions.assertThat<Transfer?>((events.first() as StepEvent).transfer).isNull()
                engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            }
            Assertions.assertThat<Transfer?>((events.get(events.size - 2) as StepEvent).transfer)
                .isEqualTo(transfer)
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(target)
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat<Transfer?>((events.get(events.size - 2) as StepEvent).transfer)
                .isEqualTo(transfer)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun onlyTakenLinkingBranchesReportCalls(delayedBranching: Boolean) {
        load(
                $$"""
                .text
                addiu $t0,$zero,-1
                bgezal $t0,target
                nop
                bgezal $zero,target
                nop
                nop
                target: nop

                """
                    .trimIndent(),
            )
            .use { session ->
                session.settings.setDelayedBranchingEnabled(delayedBranching)
                val engine: SimulatorEngine = session.simulator()
                val events = ArrayList<Any?>()
                engine.addListener(listener(events))
                val entry: Int = session.registerFile.getPC()
                engine.runAsync(4, null).get(5, TimeUnit.SECONDS)
                val steps =
                    events
                        .stream()
                        .filter { o: Any? -> StepEvent::class.java.isInstance(o) }
                        .map<StepEvent> { obj: Any? -> StepEvent::class.java.cast(obj) }
                        .toList()
                val returnAddress = entry + (if (delayedBranching) 20 else 16)
                Assertions.assertThat<StepEvent>(steps)
                    .extracting<Transfer?, RuntimeException>(StepEvent::transfer)
                    .containsExactly(null, null, null, StepEvent.Call(31, returnAddress))
                Assertions.assertThat<StepEvent>(steps)
                    .extracting<BranchOutcome?, RuntimeException>(StepEvent::branchOutcome)
                    .containsExactly(null, BranchOutcome.NOT_TAKEN, null, BranchOutcome.TAKEN)
                Assertions.assertThat(session.registerFile.getValue(31)).isEqualTo(returnAddress)
            }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun branchToTheNextInstructionReportsTakenEvenWhenTheProgramCounterLooksSequential(
        delayed: Boolean,
    ) {
        load($$".text\nbeq $zero,$zero,next\nnext: nop\n").use { session ->
            session.settings.setDelayedBranchingEnabled(delayed)
            val engine: SimulatorEngine = session.simulator()
            val events = ArrayList<Any?>()
            engine.addListener(listener(events))
            val entry: Int = session.registerFile.getPC()
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(entry + 4)
            engine.backstepAsync().get(5, TimeUnit.SECONDS)
            engine.runAsync(2, null).get(5, TimeUnit.SECONDS)
            Assertions.assertThat<StepEvent>(
                    events
                        .stream()
                        .filter { o: Any? -> StepEvent::class.java.isInstance(o) }
                        .map<StepEvent> { obj: Any? -> StepEvent::class.java.cast(obj) },
                )
                .extracting<BranchOutcome?, RuntimeException>(StepEvent::branchOutcome)
                .containsExactly(BranchOutcome.TAKEN, BranchOutcome.TAKEN, null)
        }
    }

    @Test
    fun interruptBeforeABranchDoesNotReportAnOutcome() {
        load($$".text\nloop: beq $zero,$zero,loop\n.ktext 0x80000180\nnop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            val events = ArrayList<Any?>()
            engine.addListener(listener(events))
            session.requestExternalInterrupt(EXTERNAL_INTERRUPT_KEYBOARD)
            engine.runAsync(1, null).get(5, TimeUnit.SECONDS)
            val event = events.first() as StepEvent
            Assertions.assertThat<Transfer?>(event.transfer).isEqualTo(ExceptionEntry.INSTANCE)
            Assertions.assertThat<BranchOutcome?>(event.branchOutcome).isNull()
        }
    }

    @Test
    fun workerBreakpointCanStopBeforeEntryAndReevaluateAChangedAddressOnResume() {
        load($$".text\naddiu $t0,$zero,7\naddiu $t0,$t0,2\nnop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            val entry: Int = session.registerFile.getPC()
            val breakpoint = AtomicInteger(entry)
            val events = ArrayList<Any?>()
            engine.addListener(listener(events))
            Assertions.assertThat<StopEvent>(
                    engine
                        .runUntilAsync(
                            0,
                            IntPredicate { pc: Int -> pc == breakpoint.get() },
                        )
                        .get(5, TimeUnit.SECONDS),
                )
                .isEqualTo(StopEvent(StopEvent.Reason.BREAKPOINT, 0))
            Assertions.assertThat<Any?>(events)
                .containsExactly(StopEvent(StopEvent.Reason.BREAKPOINT, 0))
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(entry)
            Assertions.assertThat(session.registerFile.getValue(8)).isZero()

            val checkedEntry = CountDownLatch(1)
            val release = CountDownLatch(1)
            val resumed: CompletableFuture<StopEvent> =
                engine.runUntilAsync(
                    0,
                    IntPredicate { pc: Int ->
                        if (pc == entry) {
                            checkedEntry.countDown()
                            try {
                                check(release.await(5, TimeUnit.SECONDS)) { "release timeout" }
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw IllegalStateException(e)
                            }
                        }
                        pc == breakpoint.get()
                    },
                )
            try {
                Assertions.assertThat(checkedEntry.await(5, TimeUnit.SECONDS)).isTrue()
                breakpoint.set(entry + 4)
            } finally {
                release.countDown()
            }
            Assertions.assertThat<StopEvent.Reason?>(resumed.get(5, TimeUnit.SECONDS)!!.reason)
                .isEqualTo(StopEvent.Reason.BREAKPOINT)
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(entry + 4)
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(7)
            Assertions.assertThat<Any?>(events.get(1)).isInstanceOf(StepEvent::class.java)
            Assertions.assertThat<Any?>(events.get(2))
                .isEqualTo(StopEvent(StopEvent.Reason.BREAKPOINT, 0))
        }
    }

    @Test
    fun stoppingWhileTheWorkerChecksABreakpointWinsWithoutExecutingTheInstruction() {
        load($$".text\naddiu $t0,$zero,7\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            val result: CompletableFuture<StopEvent> =
                engine.runUntilAsync(
                    0,
                    IntPredicate { pc: Int ->
                        engine.stop()
                        true
                    },
                )
            Assertions.assertThat<StopEvent.Reason?>(result.get(5, TimeUnit.SECONDS)!!.reason)
                .isEqualTo(StopEvent.Reason.STOP_REQUEST)
            Assertions.assertThat(session.registerFile.getValue(8)).isZero()
        }
    }

    @Test
    fun pauseStopAndMutationRejectionOccurAtInstructionBoundaries() {
        load($$".text\nloop: addiu $t0,$t0,1\nb loop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocker: SimulatorListener? =
                object : SimulatorListener {
                    override fun onStep(event: StepEvent) {
                        entered.countDown()
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw AssertionError("release timeout")
                            }
                        } catch (e: InterruptedException) {
                            throw AssertionError(e)
                        }
                    }

                    override fun onStop(event: StopEvent) {}

                    override fun onError(event: ErrorEvent) {}
                }
            engine.addListener(blocker!!)
            val run: CompletableFuture<StopEvent> = engine.runAsync(0, null)
            Assertions.assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            try {
                Assertions.assertThatThrownBy(ThrowableAssert.ThrowingCallable { engine.step() })
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable { engine.backstep() },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable { session.editIntegerRegister(8, 42) },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            session.editMemoryWord(
                                session.memory.dataBaseAddress,
                                42,
                            )
                        },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            session.editCoprocessor0Register(
                                Coprocessor0State.EPC,
                                42,
                            )
                        },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            session.editCoprocessor1Register(
                                0,
                                42,
                            )
                        },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable { session.editCoprocessor1Pair(0, 42) },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            session.editCoprocessor1ConditionFlags(
                                1,
                            )
                        },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            session.setMemoryConfiguration("Default")
                        },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            session.assemble(
                                listOf(
                                    directory.resolve("none"),
                                ),
                            )
                        },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            Objects.requireNonNull<MIPSprogram>(
                                    session.program,
                                )!!
                                .simulate(1)
                        },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                engine.pause()
            } finally {
                release.countDown()
            }
            Assertions.assertThat<StopEvent.Reason?>(run.get(5, TimeUnit.SECONDS)!!.reason)
                .isEqualTo(StopEvent.Reason.PAUSE)
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(1)
            engine.removeListener(blocker)
            val resumed: CompletableFuture<StopEvent> = engine.runAsync(0, null)
            engine.stop()
            Assertions.assertThat<StopEvent.Reason?>(resumed.get(5, TimeUnit.SECONDS)!!.reason)
                .isEqualTo(StopEvent.Reason.STOP_REQUEST)
            Assertions.assertThat<SimulatorState?>(engine.state()).isEqualTo(SimulatorState.STOPPED)
        }
    }

    @Test
    fun separateWorkersRunWithoutInterferenceAndCanLoadNewPrograms() {
        load($$".text\naddiu $t0,$zero,42\nli $a0,7\nli $v0,17\nsyscall\n").use { first ->
            load($$".text\naddiu $t0,$zero,99\n").use { second ->
                val a: CompletableFuture<StopEvent> = first.simulator().runAsync(0, null)
                val b: CompletableFuture<StopEvent> = second.simulator().runAsync(0, null)
                Assertions.assertThat<StopEvent>(a.get(5, TimeUnit.SECONDS))
                    .isEqualTo(StopEvent(StopEvent.Reason.NORMAL_EXIT, 7))
                Assertions.assertThat<StopEvent.Reason?>(b.get(5, TimeUnit.SECONDS)!!.reason)
                    .isEqualTo(StopEvent.Reason.CLIFF_TERMINATION)
                Assertions.assertThat(first.registerFile.getValue(8)).isEqualTo(42)
                Assertions.assertThat(second.registerFile.getValue(8)).isEqualTo(99)
                val file = directory.resolve("replacement.s")
                Files.writeString(file, $$".text\naddiu $t0,$zero,12\n")
                first.assemble(listOf(file))
                Assertions.assertThat<StopEvent.Reason?>(
                        first.simulator().runAsync(0, null).get(5, TimeUnit.SECONDS).reason,
                    )
                    .isEqualTo(StopEvent.Reason.CLIFF_TERMINATION)
                Assertions.assertThat(first.registerFile.getValue(8)).isEqualTo(12)
            }
        }
    }

    @Test
    fun runtimeFailureEmitsErrorThenStopAndPreservesUndo() {
        load($$".text\nli $t0,0x7fffffff\naddi $t0,$t0,1\n").use { session ->
            val events = ArrayList<Any?>()
            session.simulator().addListener(listener(events))
            Assertions.assertThat<StopEvent.Reason?>(
                    session.simulator().runAsync(0, null).get(5, TimeUnit.SECONDS).reason,
                )
                .isEqualTo(StopEvent.Reason.EXCEPTION)
            Assertions.assertThat<Any?>(events.get(events.size - 2))
                .isInstanceOf(ErrorEvent::class.java)
            Assertions.assertThat<Any?>(events.last())
                .isEqualTo(StopEvent(StopEvent.Reason.EXCEPTION, 0))
            Assertions.assertThat(session.backStepper.empty()).isFalse()
            session.simulator().backstepAsync().get(5, TimeUnit.SECONDS)
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(Int.MAX_VALUE)
        }
    }

    @Test
    fun assemblyApiReturnsProgramAndSourceDiagnostics() {
        TestSessions.create().use { session ->
            val assembler = SessionAssembler(session)
            val source = directory.resolve("api.s")
            Files.writeString(source, $$".text\naddiu $t0,$zero,1\n")
            val success: AssemblyResult = assembler.assemble(listOf(source))
            Assertions.assertThat(success.succeeded()).isTrue()
            Assertions.assertThat(
                    Objects.requireNonNull<Program?>(success.program())!!.instructionCount(),
                )
                .isEqualTo(1)
            Files.writeString(source, $$".text\nunknown $t0\n")
            val failure: AssemblyResult = assembler.assemble(listOf(source))
            Assertions.assertThat(failure.succeeded()).isFalse()
            Assertions.assertThat<Program?>(failure.program()).isNull()
            Assertions.assertThat(failure.errors().first().file).isEqualTo(source)
            Assertions.assertThat(failure.errors().first().line).isEqualTo(2)
        }
    }

    @Test
    fun closingStopsTheWorkerAtABoundaryAndRejectsNewFrontendWork() {
        load($$".text\nloop: addiu $t0,$t0,1\nb loop\n").use { session ->
            val engine: SimulatorEngine = session.simulator()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val rejected = AtomicBoolean()
            engine.addListener(
                object : SimulatorListener {
                    override fun onStep(event: StepEvent) {
                        try {
                            session.setMemoryConfiguration("Default")
                        } catch (expected: IllegalStateException) {
                            rejected.set(true)
                        }
                        entered.countDown()
                        try {
                            Assertions.assertThat(release.await(5, TimeUnit.SECONDS)).isTrue()
                        } catch (failure: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }

                    override fun onStop(event: StopEvent) {}

                    override fun onError(event: ErrorEvent) {}
                },
            )
            val run: CompletableFuture<StopEvent> = engine.runAsync(0, null)
            Assertions.assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            try {
                Assertions.assertThat(rejected).isTrue()
                session.close()
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            session.setMemoryConfiguration("Default")
                        },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(ThrowableAssert.ThrowingCallable { engine.run() })
                    .isInstanceOf(IllegalStateException::class.java)
            } finally {
                release.countDown()
            }
            Assertions.assertThat<StopEvent.Reason?>(run.get(5, TimeUnit.SECONDS)!!.reason)
                .isEqualTo(StopEvent.Reason.STOP_REQUEST)
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(1)
        }
    }

    private fun load(source: String): MarsContext {
        val file = directory.resolve(UUID.randomUUID().toString() + ".s")
        Files.writeString(file, source)
        val session = TestSessions.create()
        session.assemble(listOf(file))
        return session
    }

    companion object {
        private fun listener(events: MutableList<Any?>): SimulatorListener {
            return object : SimulatorListener {
                override fun onStep(event: StepEvent) {
                    events.add(event)
                }

                override fun onBackstep(stepCount: Long) {
                    events.add(stepCount)
                }

                override fun onStop(event: StopEvent) {
                    events.add(event)
                }

                override fun onError(event: ErrorEvent) {
                    events.add(event)
                }
            }
        }
    }
}
