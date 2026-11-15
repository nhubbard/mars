package edu.missouristate.mars

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class Coprocessor0UndoIsolationTest {
    private val machine: MarsContext = TestSessions.create()

    @AfterEach
    fun closeMachine() {
        machine.close()
    }

    @ParameterizedTest
    @ValueSource(ints = [8, 12, 13, 14])
    fun facadePreservesBacksteppingAndNamedWritesRemainUnrecorded(number: Int) {
        val previousPc = machine.registerFile.programCounter
        val previousValue = machine.coprocessor0.getValue(number)
        try {
            val program = MIPSprogram(machine)
            val path = SimulatorSmokeTest.resourcePath("01_helloworld.asm")
            program.readSource(path)
            program.tokenize()
            val filenames = arrayListOf(path)
            program.assemble(program.prepareFilesForAssembly(filenames, path, null), extended = true, warnings = false)
            val backStepper = checkNotNull(program.backStepper)
            machine.registerFile.initializeProgramCounter(
                machine.registerFile.initialProgramCounter + 4,
            )

            machine.backStepper.clear()
            val independent = Coprocessor0State()
            independent.updateRegister(number, 101)
            Assertions.assertThat(backStepper.empty()).isTrue()
            Assertions.assertThat(machine.coprocessor0.getValue(number)).isEqualTo(previousValue)
            Assertions.assertThat(TestSessions.execution(machine).updateCoprocessor0(number, 42))
                .isEqualTo(previousValue)
            Assertions.assertThat(backStepper.empty()).isFalse()
            backStepper.backStep()
            Assertions.assertThat(machine.coprocessor0.getValue(number)).isEqualTo(previousValue)
            Assertions.assertThat(independent.getValue(number)).isEqualTo(101)
            Assertions.assertThat(backStepper.empty()).isTrue()

            // The original string overload updates the register without recording undo history.
            Assertions.assertThat(machine.coprocessor0.updateRegister("$$number", 43))
                .isEqualTo(previousValue)
            Assertions.assertThat(machine.coprocessor0.getValue(number)).isEqualTo(43)
            Assertions.assertThat(backStepper.empty()).isTrue()
        } finally {
            TestSessions.execution(machine).updateCoprocessor0(number, previousValue)
            machine.registerFile.initializeProgramCounter(previousPc)
        }
    }
}
