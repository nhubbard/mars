package edu.missouristate.mars

import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile
import edu.missouristate.mars.mips.instructions.basic.InstrAddi
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

internal class IntegerRegisterUndoIsolationTest {
  private val machine: MarsContext = TestSessions.create()

  @AfterEach
  fun closeMachine() {
    machine.close()
  }

  @Test
  fun facadePreservesBacksteppingWithoutRecordingIndependentInstanceWrites() {
    val previousValues = IntArray(35)
    for (i in 0..34) {
      previousValues[i] =
          if (i == 32) machine.registerFile.programCounter else machine.registerFile.getValue(i)
    }
    try {
      val program = MIPSprogram(machine)
      val path = SimulatorSmokeTest.resourcePath("01_helloworld.asm")
      program.readSource(path)
      program.tokenize()
      val filenames = arrayListOf(path)
      program.assemble(
          program.prepareFilesForAssembly(filenames, path, null),
          extended = true,
          warnings = false,
      )
      val backStepper = checkNotNull(program.backStepper)
      val pc = machine.registerFile.initialProgramCounter
      machine.registerFile.initializeProgramCounter(pc + 4)
      val previousT0 = machine.registerFile.getValue(8)
      val previousHi = machine.registerFile.getValue(33)
      val previousLo = machine.registerFile.getValue(34)

      machine.backStepper.clear()
      val independent = IntegerRegisterFile()
      independent.setValue(8, 99)
      independent.setValue(33, 100)
      independent.setValue(32, 101)
      Assertions.assertThat(backStepper.empty()).isTrue()
      Assertions.assertThat(machine.registerFile.getValue(8)).isEqualTo(previousT0)
      Assertions.assertThat(TestSessions.execution(machine).updateRegister(8, 42))
          .isEqualTo(previousT0)
      TestSessions.execution(machine).updateRegister(33, 43)
      TestSessions.execution(machine).updateRegister(34, 44)
      Assertions.assertThat(backStepper.empty()).isFalse()
      backStepper.backStep()
      Assertions.assertThat(machine.registerFile.getValue(8)).isEqualTo(previousT0)
      Assertions.assertThat(machine.registerFile.getValue(33)).isEqualTo(previousHi)
      Assertions.assertThat(machine.registerFile.getValue(34)).isEqualTo(previousLo)
      Assertions.assertThat(independent.getValue(8)).isEqualTo(99)

      // The explicit compatibility-session invocation must keep recording undo history.
      machine.registerFile.initializeProgramCounter(pc + 4)
      val add = InstrAddi()
      val statement = ProgramStatement(null, $$"addi $t0,$zero,42", null, null, add, pc, 1)
      intArrayOf(8, 0, 42).copyInto(checkNotNull(statement.operands))
      add.simulationCode.simulate(
          statement,
          InstructionContext(
              machine,
              machine.syscallEnvironment,
          ),
      )
      Assertions.assertThat(machine.registerFile.getValue(8)).isEqualTo(42)
      Assertions.assertThat(backStepper.empty()).isFalse()
      backStepper.backStep()
      Assertions.assertThat(machine.registerFile.getValue(8)).isEqualTo(previousT0)

      machine.registerFile.initializeProgramCounter(pc + 4)
      Assertions.assertThat(TestSessions.execution(machine).setProgramCounter(pc + 8))
          .isEqualTo(pc + 4)
      backStepper.backStep()
      // The legacy back-stepper restores the address before the automatic PC increment.
      Assertions.assertThat(machine.registerFile.programCounter).isEqualTo(pc)
      TestSessions.execution(machine).updateRegister(0, 1000)
      Assertions.assertThat(machine.registerFile.getValue(0)).isZero()
    } finally {
      for (i in 1..34) {
        if (i == 32) {
          machine.registerFile.initializeProgramCounter(previousValues[i])
        } else {
          TestSessions.execution(machine).updateRegister(i, previousValues[i])
        }
      }
    }
  }
}
