package edu.missouristate.mars

import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.core.SimulationSettings
import edu.missouristate.mars.mips.hardware.MemoryConfigurations
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.instructions.basic.InstrSw
import edu.missouristate.mars.simulator.BackStepper
import edu.missouristate.mars.simulator.BackStepper.MemoryWrite
import java.util.Objects
import java.util.function.BiFunction
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class MemoryUndoIsolationTest {
    private val machine: MarsContext = TestSessions.create()

    @AfterEach
    fun closeMachine() {
        machine.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["word", "half", "byte", "raw", "instruction"])
    fun legacyWritesKeepUndoHistoryWhileIndependentWritesDoNot(operation: String) {
        val previousPc = machine.registerFile.programCounter
        val previousT0 = machine.registerFile.getValue(8)
        val previousT1 = machine.registerFile.getValue(9)
        try {
            val program = MIPSprogram(machine)
            val path = SimulatorSmokeTest.resourcePath("01_helloworld.asm")
            program.readSource(path)
            program.tokenize()
            val files = arrayListOf(path)
            program.assemble(program.prepareFilesForAssembly(files, path, null), extended = true, warnings = false)
            val address = machine.memory.dataBaseAddress + 0x100
            TestSessions.execution(machine).setMemoryWord(address, 0x12345678)
            TestSessions.execution(machine).updateRegister(8, 0x76543210)
            TestSessions.execution(machine).updateRegister(9, address)
            machine.registerFile.initializeProgramCounter(machine.memory.textBaseAddress + 4)
            val backStepper = Objects.requireNonNull<BackStepper>(program.backStepper)
            machine.backStepper.clear()
            machine.backStepper.clear()
            val independent =
                MemoryState(
                    MemoryConfigurations.createConfiguration(
                        "Default",
                    ),
                    SimulationSettings(),
                    BiFunction { binary: Int?, pc: Int? ->
                        ProgramStatement(binary!!, pc!!, machine.instructionSet)
                    },
                )
            independent.setWord(address, 99)
            Assertions.assertThat(backStepper.empty()).isTrue()
            when (operation) {
                "word" -> TestSessions.execution(machine).setMemoryWord(address, 0x76543210)
                "half" -> TestSessions.execution(machine).setMemoryHalf(address, 0x3210)
                "byte" -> TestSessions.execution(machine).setMemoryByte(address, 0x10)
                "raw" -> TestSessions.execution(machine).setMemoryRawWord(address, 0x76543210)
                "instruction" -> {
                    val store = InstrSw()
                    val statement =
                        ProgramStatement(
                            null,
                            $$"sw $t0,0($t1)",
                            null,
                            null,
                            store,
                            machine.memory.textBaseAddress,
                            1,
                        )
                    System.arraycopy(
                        intArrayOf(8, 0, 9),
                        0,
                        Objects.requireNonNull<IntArray?>(statement.operands),
                        0,
                        3,
                    )
                    store.simulationCode.simulate(
                        statement,
                        InstructionContext(
                            machine,
                            machine.syscallEnvironment,
                        ),
                    )
                }

                else -> throw IllegalArgumentException(operation)
            }
            Assertions.assertThat(backStepper.empty()).isFalse()
            backStepper.backStep()
            Assertions.assertThat(machine.memory.getWord(address)).isEqualTo(0x12345678)
            Assertions.assertThat(independent.getWord(address)).isEqualTo(99)
        } finally {
            TestSessions.execution(machine).updateRegister(8, previousT0)
            TestSessions.execution(machine).updateRegister(9, previousT1)
            machine.registerFile.initializeProgramCounter(previousPc)
        }
    }

    @Test
    fun lastProgramWriteOmitsUngroupedWritesAndClearedHistory() {
        val pc = machine.memory.textBaseAddress
        val address = machine.memory.dataBaseAddress
        val history = machine.backStepper
        val execution = TestSessions.execution(machine)
        history.beginInstruction(pc, ProgramStatement(0, pc, machine.instructionSet))
        Assertions.assertThat(execution.setMemoryRawWord(address, 42)).isEqualTo(0)
        history.endInstruction()
        execution.setMemoryWord(address + Integer.BYTES, 99)

        Assertions.assertThat<MemoryWrite?>(history.previousMemoryWrite())
            .isEqualTo(MemoryWrite(address, Integer.BYTES))
        history.setEnabled(false)
        Assertions.assertThat<MemoryWrite?>(history.previousMemoryWrite()).isNull()
        history.setEnabled(true)
        history.clear()
        Assertions.assertThat<MemoryWrite?>(history.previousMemoryWrite()).isNull()
    }

    @Test
    fun legacyConfigurationAndHeapMirrorsStayCompatibleWithoutAffectingSessionMemory() {
        val previousConfiguration = machine.memory.getConfiguration()
        val previousPc = machine.registerFile.programCounter
        val previousValues = IntArray(35)
        for (i in 0..34) {
            previousValues[i] = if (i == 32) previousPc else machine.registerFile.getValue(i)
        }
        try {
            val session =
                MemoryState(
                    MemoryConfigurations.createConfiguration(
                        "CompactTextAtZero",
                    ),
                    SimulationSettings(),
                    BiFunction { binary: Int?, address: Int? ->
                        ProgramStatement(
                            binary!!,
                            address!!,
                            machine.instructionSet,
                        )
                    },
                )
            session.setWord(session.dataBaseAddress, 42)
            session.allocateBytesFromHeap(8)
            val singleton = machine.memory
            TestSessions.select(
                machine,
                requireNotNull(
                    MemoryConfigurations.getConfigurationByName("CompactDataAtZero"),
                ),
            )
            Assertions.assertThat<MemoryState?>(machine.memory).isSameAs(singleton)
            Assertions.assertThat(machine.memory.textBaseAddress).isEqualTo(0x3000)
            Assertions.assertThat(machine.memory.dataBaseAddress).isZero()
            Assertions.assertThat(machine.memory.inTextSegment(0x3000)).isTrue()
            Assertions.assertThat(machine.registerFile.programCounter).isEqualTo(0x3000)
            Assertions.assertThat(machine.registerFile.getValue(29)).isEqualTo(0x2ffc)
            Assertions.assertThat(machine.memory.allocateBytesFromHeap(5)).isEqualTo(0x2000)
            Assertions.assertThat(machine.memory.heapAddress).isEqualTo(0x2008)
            machine.memory.clear()
            Assertions.assertThat(machine.memory.heapAddress).isEqualTo(0x2000)
            Assertions.assertThat(session.textBaseAddress).isZero()
            Assertions.assertThat(session.getWord(session.dataBaseAddress)).isEqualTo(42)
            Assertions.assertThat(session.heapAddress).isEqualTo(session.heapBaseAddress + 8)
        } finally {
            TestSessions.select(machine, previousConfiguration)
            for (i in 1..34) {
                if (i != 32) {
                    TestSessions.execution(machine).updateRegister(i, previousValues[i])
                }
            }
            machine.registerFile.initializeProgramCounter(previousPc)
        }
    }
}
