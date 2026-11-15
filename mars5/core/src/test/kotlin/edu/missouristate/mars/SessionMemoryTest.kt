package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.api.MemoryAccessException
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.basic.InstrLdc1
import edu.missouristate.mars.mips.instructions.basic.InstrLwc1
import edu.missouristate.mars.mips.instructions.basic.InstrSdc1
import edu.missouristate.mars.mips.instructions.basic.InstrSwc1
import edu.missouristate.mars.mips.instructions.basic.InstrSyscall
import java.util.Objects
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.Test

internal class SessionMemoryTest {
    @Test
    fun memoryAndDecoderCanStartWithoutGlobalSimulationObjects() {
        component().context().use { session ->
            session.settings.setSelfModifyingCodeEnabled(true)
            session.memory.setWord(session.memory.textBaseAddress, 0x2408002a)
            val statement =
                requireNotNull(session.memory.getStatement(session.memory.textBaseAddress))
            Assertions.assertThat(
                    Objects.requireNonNull(statement.instruction)!!.name,
                )
                .isEqualTo("addiu")
            Assertions.assertThat(statement.printableBasicAssemblyStatement).contains("addiu")
            val context = InstructionContext(session, RecordingEnvironment())
            context.execute(statement)
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(42)
            session.backStepper.backStep()
            Assertions.assertThat(session.registerFile.getValue(8)).isZero()
            Assertions.assertThat(session.registerFile.getPC())
                .isEqualTo(session.memory.textBaseAddress)
        }
    }

    @Test
    fun interleavedProgramsFetchAndStoreInTheirOwnMemoryAndAllocateTheirOwnHeap() {
        component().context().use { first ->
            component().context().use { second ->
                val firstContext = InstructionContext(first, RecordingEnvironment())
                val secondContext = InstructionContext(second, RecordingEnvironment())
                installProgram(first, 42)
                installProgram(second, 99)
                var firstDone = false
                var secondDone = false
                var i = 0
                while (i < 20 && !(firstDone && secondDone)) {
                    if (!firstDone) {
                        firstDone = step(firstContext)
                    }
                    if (!secondDone) {
                        secondDone = step(secondContext)
                    }
                    i++
                }
                Assertions.assertThat(firstDone).isTrue()
                Assertions.assertThat(secondDone).isTrue()
                Assertions.assertThat(first.memory.getWord(0x10010000)).isEqualTo(42)
                Assertions.assertThat(second.memory.getWord(0x10010000)).isEqualTo(99)
                Assertions.assertThat(first.registerFile.getValue(10)).isEqualTo(42)
                Assertions.assertThat(second.registerFile.getValue(10)).isEqualTo(99)
                Assertions.assertThat(first.registerFile.getValue(16))
                    .isEqualTo(first.memory.heapBaseAddress)
                Assertions.assertThat(second.registerFile.getValue(16))
                    .isEqualTo(second.memory.heapBaseAddress)
                Assertions.assertThat(first.memory.heapAddress)
                    .isEqualTo(first.memory.heapBaseAddress + 8)
                Assertions.assertThat(second.memory.heapAddress)
                    .isEqualTo(second.memory.heapBaseAddress + 8)
                first.memory.clear()
                Assertions.assertThat(second.memory.getWord(0x10010000)).isEqualTo(99)
                Assertions.assertThat<ProgramStatement>(
                        second.memory.getStatement(second.memory.textBaseAddress),
                    )
                    .isNotNull()
            }
        }
    }

    @Test
    fun floatingPointLoadAndStoreUseTheInvocationsMemory() {
        component().context().use { firstSession ->
            component().context().use { secondSession ->
                val first = InstructionContext(firstSession, RecordingEnvironment())
                val second = InstructionContext(secondSession, RecordingEnvironment())
                val address = first.memory().dataBaseAddress
                first.registerFile.setValue(8, address)
                second.registerFile.setValue(8, address)
                first.coprocessor1.setRegisterToFloat(0, 1.5f)
                second.coprocessor1.setRegisterToFloat(0, 2.5f)
                execute(InstrSwc1(), first, 0, 0, 8)
                execute(InstrSwc1(), second, 0, 0, 8)
                execute(InstrLwc1(), first, 2, 0, 8)
                execute(InstrLwc1(), second, 2, 0, 8)
                Assertions.assertThat(first.coprocessor1.getFloatFromRegister(2)).isEqualTo(1.5f)
                Assertions.assertThat(second.coprocessor1.getFloatFromRegister(2)).isEqualTo(2.5f)
                first.coprocessor1.setRegisterPairToDouble(4, 3.5)
                second.coprocessor1.setRegisterPairToDouble(4, 4.5)
                execute(InstrSdc1(), first, 4, 8, 8)
                execute(InstrSdc1(), second, 4, 8, 8)
                execute(InstrLdc1(), first, 6, 8, 8)
                execute(InstrLdc1(), second, 6, 8, 8)
                Assertions.assertThat(first.coprocessor1.getDoubleFromRegisterPair(6))
                    .isEqualTo(3.5)
                Assertions.assertThat(second.coprocessor1.getDoubleFromRegisterPair(6))
                    .isEqualTo(4.5)
            }
        }
    }

    @Test
    fun stringAndInputSyscallsUseTheOwnersMemoryAndEnvironment() {
        val firstIo = RecordingEnvironment()
        val secondIo = RecordingEnvironment()
        component().context().use { firstSession ->
            component().context().use { secondSession ->
                val first = InstructionContext(firstSession, firstIo)
                val second = InstructionContext(secondSession, secondIo)
                val address = first.memory().dataBaseAddress
                writeString(first, address, "first")
                writeString(second, address, "second")
                first.registerFile.setValue(4, address)
                second.registerFile.setValue(4, address)
                first.registerFile.setValue(2, 4)
                second.registerFile.setValue(2, 4)
                execute(InstrSyscall(), first)
                execute(InstrSyscall(), second)
                Assertions.assertThat(firstIo.printed.joinToString("")).isEqualTo("first")
                Assertions.assertThat(secondIo.printed.joinToString("")).isEqualTo("second")
                firstIo.input = "alpha"
                secondIo.input = "beta"
                first.registerFile.setValue(2, 8)
                second.registerFile.setValue(2, 8)
                first.registerFile.setValue(5, 16)
                second.registerFile.setValue(5, 16)
                execute(InstrSyscall(), first)
                execute(InstrSyscall(), second)
                Assertions.assertThat(first.memory().getByte(address)).isEqualTo('a'.code)
                Assertions.assertThat(second.memory().getByte(address)).isEqualTo('b'.code)
            }
        }
    }

    @Test
    fun switchingSessionConfigurationResetsItsMemoryAndPointersOnly() {
        component().context().use { first ->
            component().context().use { second ->
                first.memory.setWord(first.memory.dataBaseAddress, 42)
                second.memory.setWord(second.memory.dataBaseAddress, 99)
                second.memory.allocateBytesFromHeap(8)
                val legacyText: Int = second.memory.textBaseAddress
                first.setMemoryConfiguration("CompactTextAtZero")
                Assertions.assertThat(first.memory.textBaseAddress).isZero()
                Assertions.assertThat(first.registerFile.getPC()).isZero()
                Assertions.assertThat(first.registerFile.getValue(28)).isEqualTo(0x1800)
                Assertions.assertThat(first.registerFile.getValue(29)).isEqualTo(0x3ffc)
                Assertions.assertThat(first.memory.getWord(first.memory.dataBaseAddress)).isZero()
                Assertions.assertThat(first.settings.memoryConfiguration)
                    .isEqualTo("CompactTextAtZero")
                Assertions.assertThat(second.memory.textBaseAddress).isEqualTo(0x400000)
                Assertions.assertThat(second.memory.getWord(second.memory.dataBaseAddress))
                    .isEqualTo(99)
                Assertions.assertThat(second.memory.heapAddress)
                    .isEqualTo(second.memory.heapBaseAddress + 8)
                Assertions.assertThat(second.memory.textBaseAddress).isEqualTo(legacyText)
            }
        }
    }

    @Test
    fun publicMemoryApiUsesTheSameStateAndTranslatesAddressErrors() {
        val component: MarsComponent = component()
        component.context().use { session ->
            val api = component.memory()
            val memory: MemoryState = session.memory
            val address = memory.dataBaseAddress
            api.setWord(address, 0x1234abcd)
            Assertions.assertThat(memory.getWord(address)).isEqualTo(0x1234abcd)
            Assertions.assertThat(api.getWord(address)).isEqualTo(0x1234abcd)
            Assertions.assertThat(api.getHalfWord(address)).isEqualTo(0xabcd)
            Assertions.assertThat(api.getByte(address)).isEqualTo(0xcd)
            Assertions.assertThatExceptionOfType(
                    MemoryAccessException::class.java,
                )
                .isThrownBy { api.getWord(address + 1) }
                .satisfies(
                    ThrowingConsumer { error: MemoryAccessException ->
                        Assertions.assertThat(error.address).isEqualTo(address + 1)
                        Assertions.assertThat(error.cause)
                            .isInstanceOf(AddressErrorException::class.java)
                    },
                )
            api.clear()
            Assertions.assertThat(memory.getWord(address)).isZero()
        }
    }

    private class RecordingEnvironment : TestEnvironment() {
        val printed: MutableList<String?> = ArrayList()
        var input: String = ""

        override fun printString(s: String?) {
            printed.add(s)
        }

        override fun printChar(c: Char) {
            printed.add(c.toString())
        }

        override fun readString(maxLength: Int): String? {
            return input
        }
    }

    companion object {
        private fun component(): MarsComponent {
            val settings = Settings()
            settings.simulationSettings.setMemoryConfiguration("Default")
            return DaggerMarsComponent.builder()
                .coreModule(CoreModule(settings, target = MarsTarget.MARS32))
                .guiProxy(NO_GUI)
                .build()
        }

        private fun installProgram(session: MarsContext, value: Int) {
            val words =
                intArrayOf(
                    0x3c091001,
                    0x24080000 or value,
                    -0x52d80000,
                    -0x72d60000,
                    0x24020009,
                    0x24040005,
                    0x0000000c,
                    0x00408021,
                    0x2402000a,
                    0x0000000c,
                )
            val base = session.memory.textBaseAddress
            for (i in words.indices) {
                val address: Int = base + i * 4
                session.memory.setStatement(
                    address,
                    ProgramStatement(words[i], address, session.instructionSet),
                )
            }
            session.registerFile.initializeProgramCounter(base)
        }

        private fun step(context: InstructionContext): Boolean {
            val statement = context.memory().getStatement(context.registerFile.getPC())
            Assertions.assertThat<ProgramStatement>(statement).isNotNull()
            try {
                context.execute(statement!!)
                return false
            } catch (error: ProcessingException) {
                if (error.errors() != null) {
                    throw error
                }
                return true
            }
        }

        private fun execute(
            instruction: BasicInstruction,
            context: InstructionContext,
            vararg operands: Int,
        ) {
            val statement =
                ProgramStatement(
                    null,
                    "test",
                    null,
                    null,
                    instruction,
                    context.memory().textBaseAddress,
                    1,
                )
            System.arraycopy(
                operands,
                0,
                Objects.requireNonNull<IntArray?>(statement.operands),
                0,
                operands.size,
            )
            instruction.simulationCode.simulate(statement, context)
        }

        private fun writeString(context: InstructionContext, address: Int, value: String) {
            for (i in 0..<value.length) {
                context.memory().setByte(address + i, value[i].code)
            }
            context.memory().setByte(address + value.length, 0)
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
