package edu.missouristate.mars

import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.SimulationCode
import edu.missouristate.mars.mips.instructions.basic.InstrAdd
import edu.missouristate.mars.mips.instructions.basic.InstrAddD
import edu.missouristate.mars.mips.instructions.basic.InstrAddiu
import edu.missouristate.mars.mips.instructions.basic.InstrBeq
import edu.missouristate.mars.mips.instructions.basic.InstrCLtD2
import edu.missouristate.mars.mips.instructions.basic.InstrEret
import edu.missouristate.mars.mips.instructions.basic.InstrNop
import edu.missouristate.mars.mips.instructions.basic.InstrSw
import edu.missouristate.mars.mips.instructions.basic.InstrSyscall
import edu.missouristate.mars.simulator.DelayedBranchState
import java.util.Objects
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class SessionBackStepperTest {
    @Test
    fun undoRestoresOnlyTheOwningSessionsRegistersMemoryAndPc() {
        val first: MarsContext = session(32)
        val second: MarsContext = session(32)
        val firstExecution: InstructionContext = execution(first)
        val secondExecution: InstructionContext = execution(second)
        val base = first.memory.textBaseAddress
        val address = first.memory.dataBaseAddress
        first.registerFile.setValue(9, address)
        second.registerFile.setValue(9, address)
        firstExecution.execute(statement(InstrAddiu(), 8, 0, 42))
        secondExecution.execute(statement(InstrAddiu(), 8, 0, 99))
        firstExecution.execute(statement(InstrSw(), 8, 0, 9))
        secondExecution.execute(statement(InstrSw(), 8, 0, 9))
        first.backStepper.backStep()
        Assertions.assertThat(first.memory.getWord(address)).isZero()
        Assertions.assertThat(first.registerFile.getValue(8)).isEqualTo(42)
        Assertions.assertThat(first.registerFile.getPC()).isEqualTo(base + 4)
        Assertions.assertThat(second.memory.getWord(address)).isEqualTo(99)
        Assertions.assertThat(second.registerFile.getPC()).isEqualTo(base + 8)
        first.backStepper.backStep()
        Assertions.assertThat(first.registerFile.getValue(8)).isZero()
        Assertions.assertThat(first.registerFile.getPC()).isEqualTo(base)
        Assertions.assertThat(first.backStepper.empty()).isTrue()
        Assertions.assertThat(second.backStepper.empty()).isFalse()
        second.backStepper.backStep()
        second.backStepper.backStep()
        Assertions.assertThat(second.registerFile.getValue(8)).isZero()
        Assertions.assertThat(second.memory.getWord(address)).isZero()
    }

    @Test
    fun repeatedExecutionOfTheSameStatementUndoesOneIterationAtATime() {
        val session: MarsContext = session(32)
        val execution: InstructionContext = execution(session)
        val base = session.memory.textBaseAddress
        val loop: BasicInstruction =
            custom(
                { `_`: ProgramStatement?, context: InstructionContext? ->
                    context!!.updateRegister(8, context.registerFile.getValue(8) + 1)
                    context.setProgramCounter(base)
                },
            )
        val statement: ProgramStatement = statement(loop)
        for (i in 0..2) {
            execution.execute(statement)
        }
        Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(3)
        for (expected in 2 downTo 0) {
            session.backStepper.backStep()
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(expected)
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base)
            Assertions.assertThat(session.backStepper.empty()).isEqualTo(expected == 0)
        }
    }

    @Test
    fun doublePrecisionAndConditionUpdatesRemainSingleUndoSteps() {
        val session: MarsContext = session(32)
        val execution: InstructionContext = execution(session)
        val originalBits = 0x7ff8123456789abcL
        session.coprocessor1.setRegisterPairToDouble(0, 1.25)
        session.coprocessor1.setRegisterPairToDouble(2, 2.75)
        session.coprocessor1.setRegisterPairToLong(4, originalBits)
        execution.execute(statement(InstrAddD(), 4, 0, 2))
        execution.execute(statement(InstrCLtD2(), 7, 0, 2))
        Assertions.assertThat(session.coprocessor1.getConditionFlag(7)).isEqualTo(1)
        session.backStepper.backStep()
        Assertions.assertThat(session.coprocessor1.getConditionFlag(7)).isZero()
        Assertions.assertThat(session.coprocessor1.getDoubleFromRegisterPair(4)).isEqualTo(4.0)
        session.backStepper.backStep()
        Assertions.assertThat(session.coprocessor1.getLongFromRegisterPair(4))
            .isEqualTo(originalBits)
        Assertions.assertThat(session.registerFile.getPC())
            .isEqualTo(session.memory.textBaseAddress)
        Assertions.assertThat(session.backStepper.empty()).isTrue()
    }

    @Test
    fun exceptionReturnAndRuntimeFaultsUndoTheirCompleteHardwareChanges() {
        val session: MarsContext = session(32)
        val execution: InstructionContext = execution(session)
        val base = session.memory.textBaseAddress
        session.coprocessor0.updateRegister(Coprocessor0State.EPC, base + 64)
        session.coprocessor0.updateRegister(Coprocessor0State.STATUS, 0xff13)
        execution.execute(statement(InstrEret()))
        Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base + 64)
        session.backStepper.backStep()
        Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base)
        Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.STATUS))
            .isEqualTo(0xff13)
        session.coprocessor0.resetRegisters()
        session.registerFile.setValue(8, Int.MAX_VALUE)
        session.registerFile.setValue(9, 1)
        Assertions.assertThatThrownBy(
                ThrowableAssert.ThrowingCallable {
                    execution.execute(
                        statement(
                            InstrAdd(),
                            10,
                            8,
                            9,
                        ),
                    )
                },
            )
            .isInstanceOf(ProcessingException::class.java)
        Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.CAUSE)).isNotZero()
        session.backStepper.backStep()
        Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.CAUSE)).isZero()
        Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.EPC)).isZero()
        Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.STATUS))
            .isEqualTo(0xff11)
        Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base)
        execution.execute(statement(InstrNop()))
        session.backStepper.backStep()
        Assertions.assertThat(session.backStepper.empty()).isTrue()
    }

    @Test
    fun invalidInstructionsProduceUndoableExceptionState() {
        val session: MarsContext = session(16)
        val execution: InstructionContext = execution(session)
        val base = session.memory.textBaseAddress
        val invalid = ProgramStatement(null, "invalid", null, null, null, base, 1)
        Assertions.assertThatThrownBy(
                ThrowableAssert.ThrowingCallable { execution.execute(invalid) },
            )
            .isInstanceOf(ProcessingException::class.java)
        Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.CAUSE))
            .isEqualTo(10 shl 2)
        session.backStepper.backStep()
        Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.CAUSE)).isZero()
        Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base)
        Assertions.assertThat(session.backStepper.empty()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["word", "half", "byte", "raw"])
    fun memoryWritesRestoreThePreviousValue(operation: String) {
        val session: MarsContext = session(16)
        val execution: InstructionContext = execution(session)
        val address = session.memory.dataBaseAddress
        session.memory.setWord(address, 0x12345678)
        execution.execute(
            statement(
                custom(
                    { statement: ProgramStatement?, context: InstructionContext? ->
                        try {
                            when (operation) {
                                "word" -> context!!.setMemoryWord(address, 0x76543210)
                                "half" -> context!!.setMemoryHalf(address, 0xabcd)
                                "byte" -> context!!.setMemoryByte(address, 0xff)
                                "raw" -> context!!.setMemoryRawWord(address, 0x76543210)
                                else -> throw IllegalArgumentException(operation)
                            }
                        } catch (error: AddressErrorException) {
                            throw ProcessingException(context!!, statement, error)
                        }
                    },
                ),
            ),
        )
        Assertions.assertThat(session.memory.getWord(address)).isNotEqualTo(0x12345678)
        session.backStepper.backStep()
        Assertions.assertThat(session.memory.getWord(address)).isEqualTo(0x12345678)
        Assertions.assertThat(session.backStepper.empty()).isTrue()
    }

    @Test
    fun allocationAndFloatingPointHelperWritesUndoWithTheirInstruction() {
        val session: MarsContext = session(32)
        val execution: InstructionContext = execution(session)
        val base = session.memory.textBaseAddress
        session.registerFile.setValue(2, 9)
        session.registerFile.setValue(4, 5)
        execution.execute(statement(InstrSyscall()))
        Assertions.assertThat(session.memory.heapAddress)
            .isEqualTo(session.memory.heapBaseAddress + 8)
        session.backStepper.backStep()
        Assertions.assertThat(session.memory.heapAddress).isEqualTo(session.memory.heapBaseAddress)
        Assertions.assertThat(session.registerFile.getValue(2)).isEqualTo(9)
        Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base)
        session.coprocessor1.setRegisterPairToLong(0, 0x0123456789abcdefL)
        execution.execute(
            statement(
                custom(
                    { `_`: ProgramStatement?, context: InstructionContext? ->
                        try {
                            context!!.setRegisterPairToDouble(0, 3.5)
                        } catch (error: InvalidRegisterAccessException) {
                            throw AssertionError(error)
                        }
                    },
                ),
            ),
        )
        Assertions.assertThat(session.coprocessor1.getDoubleFromRegisterPair(0)).isEqualTo(3.5)
        session.backStepper.backStep()
        Assertions.assertThat(session.coprocessor1.getLongFromRegisterPair(0))
            .isEqualTo(0x0123456789abcdefL)
        Assertions.assertThat(session.backStepper.empty()).isTrue()
    }

    @Test
    fun undoingADelaySlotRestoresThePendingBranchAndAllowsReexecution() {
        val session: MarsContext = session(32)
        val execution: InstructionContext = execution(session)
        val base = session.memory.textBaseAddress
        session.settings.setDelayedBranchingEnabled(true)
        val initialBranch = session.delayedBranch.snapshot()
        execution.execute(statement(InstrBeq(), 0, 0, 3))
        Assertions.assertThat(session.backStepper.inDelaySlot()).isFalse()
        val pendingBranch = session.delayedBranch.snapshot()
        val delaySlot: ProgramStatement = statement(InstrAddiu(), 8, 0, 42)
        execution.execute(delaySlot)
        Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base + 16)
        Assertions.assertThat(session.backStepper.inDelaySlot()).isTrue()
        session.backStepper.backStep()
        Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base + 4)
        Assertions.assertThat(session.registerFile.getValue(8)).isZero()
        Assertions.assertThat<DelayedBranchState.Snapshot?>(session.delayedBranch.snapshot())
            .isEqualTo(pendingBranch)
        execution.execute(delaySlot)
        Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base + 16)
        session.backStepper.backStep()
        session.backStepper.backStep()
        Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base)
        Assertions.assertThat<DelayedBranchState.Snapshot?>(session.delayedBranch.snapshot())
            .isEqualTo(initialBranch)
        Assertions.assertThat(session.backStepper.empty()).isTrue()
    }

    @Test
    fun boundedHistoryEvictsWholeInstructionsRatherThanPartialWrites() {
        val session: MarsContext = session(3)
        val execution: InstructionContext = execution(session)
        for (value in 1..2) {
            val result = value
            execution.execute(
                statement(
                    custom(
                        { `_`: ProgramStatement?, context: InstructionContext? ->
                            context!!.updateRegister(8, result)
                            context.updateRegister(9, result)
                        },
                    ),
                ),
            )
        }
        session.backStepper.backStep()
        Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(1)
        Assertions.assertThat(session.registerFile.getValue(9)).isEqualTo(1)
        Assertions.assertThat(session.backStepper.empty()).isTrue()
    }

    @Test
    fun anOversizedInstructionLeavesNoPartialUndoAndLaterStepsRemainUsable() {
        val session: MarsContext = session(2)
        val execution: InstructionContext = execution(session)
        execution.execute(
            statement(
                custom(
                    { `_`: ProgramStatement?, context: InstructionContext? ->
                        context!!.updateRegister(8, 1)
                        context.updateRegister(9, 2)
                        context.updateRegister(10, 3)
                    },
                ),
            ),
        )
        Assertions.assertThat(session.backStepper.empty()).isTrue()
        execution.execute(statement(InstrAddiu(), 8, 0, 99))
        session.backStepper.backStep()
        Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(1)
        Assertions.assertThat(session.registerFile.getValue(9)).isEqualTo(2)
        Assertions.assertThat(session.registerFile.getValue(10)).isEqualTo(3)
        Assertions.assertThat(session.backStepper.empty()).isTrue()
    }

    @Test
    fun disablingAndClearingHistoryLeaveExecutionUsable() {
        val component: MarsComponent = component(16)
        val session = component.context()
        val execution: InstructionContext = execution(session)
        session.backStepper.setEnabled(false)
        execution.execute(statement(InstrAddiu(), 8, 0, 42))
        Assertions.assertThat(session.backStepper.empty()).isTrue()
        session.backStepper.setEnabled(true)
        execution.execute(statement(InstrNop()))
        Assertions.assertThat(session.backStepper.empty()).isFalse()
        component.memory().clear()
        Assertions.assertThat(session.backStepper.empty()).isTrue()
        execution.execute(statement(InstrNop()))
        session.setMemoryConfiguration("CompactDataAtZero")
        Assertions.assertThat(session.backStepper.empty()).isTrue()
        Assertions.assertThat(session.registerFile.getPC()).isEqualTo(0x3000)
    }

    @Test
    fun anUndoFailureRaisesAnErrorWithoutTerminatingTheProcessOrDisablingHistory() {
        val session: MarsContext = session(16)
        session.backStepper.addMemoryRestoreByte(3, 42)
        Assertions.assertThatThrownBy(
                ThrowableAssert.ThrowingCallable { session.backStepper.backStep() },
            )
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("restore memory")
        Assertions.assertThat(session.backStepper.enabled()).isTrue()
        val execution: InstructionContext = execution(session)
        execution.execute(statement(InstrNop()))
        session.backStepper.backStep()
        Assertions.assertThat(session.backStepper.empty()).isTrue()
    }

    companion object {
        private fun component(capacity: Int): MarsComponent {
            val settings = Settings()
            settings.simulationSettings
                .setMemoryConfiguration("Default")
                .setDelayedBranchingEnabled(false)
            return DaggerMarsComponent.builder()
                .coreModule(CoreModule(settings, capacity))
                .guiProxy(NO_GUI)
                .build()
        }

        private fun session(capacity: Int): MarsContext {
            return component(capacity).context()
        }

        private fun execution(session: MarsContext): InstructionContext {
            return InstructionContext(session, session.syscallEnvironment)
        }

        private fun statement(
            instruction: BasicInstruction,
            vararg operands: Int,
        ): ProgramStatement {
            val statement = ProgramStatement(null, "test", null, null, instruction, 0x400000, 1)
            System.arraycopy(
                operands,
                0,
                Objects.requireNonNull<IntArray?>(statement.operands),
                0,
                operands.size,
            )
            return statement
        }

        private fun custom(code: SimulationCode): BasicInstruction {
            return BasicInstruction(
                "test",
                BasicInstructionFormat.R_FORMAT,
                "00000000000000000000000000000000",
                code,
            )
        }

        private val NO_GUI: MarsGuiProxy =
            object : MarsGuiProxy {
                override val isGuiMode: Boolean
                    get() = false

                override fun postMessage(message: String) {}

                override fun postRunMessage(message: String) {}

                override fun readInput(maxLength: Int): String {
                    return ""
                }

                override fun readInputWithPrompt(prompt: String): String {
                    return ""
                }

                override fun clearRunConsole() {}
            }
    }
}
