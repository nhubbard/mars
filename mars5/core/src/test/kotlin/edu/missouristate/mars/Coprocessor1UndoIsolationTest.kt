package edu.missouristate.mars

import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.Coprocessor1State
import edu.missouristate.mars.mips.instructions.basic.InstrAddS
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class Coprocessor1UndoIsolationTest {
  private val machine: MarsContext = TestSessions.create()

  @AfterEach
  fun closeMachine() {
    machine.close()
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7])
  fun facadePreservesRegisterAndConditionFlagBackstepping(flag: Int) {
    val previousPc = machine.registerFile.programCounter
    val previousFlags = machine.coprocessor1.conditionFlags
    val previousValues = IntArray(32)
    for (i in 0..31) {
      previousValues[i] = machine.coprocessor1.getValue(i)
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
      val pc = machine.registerFile.initialProgramCounter + 4
      machine.registerFile.initializeProgramCounter(pc)
      machine.backStepper.clear()
      val independent = Coprocessor1State()
      independent.updateRegister(12, 101)
      independent.setConditionFlag(flag)
      Assertions.assertThat(backStepper.empty()).isTrue()
      Assertions.assertThat(TestSessions.execution(machine).updateCoprocessor1(12, 42))
          .isEqualTo(previousValues[12])
      Assertions.assertThat(backStepper.empty()).isFalse()
      backStepper.backStep()
      Assertions.assertThat(machine.coprocessor1.getValue(12)).isEqualTo(previousValues[12])
      Assertions.assertThat(independent.getValue(12)).isEqualTo(101)
      Assertions.assertThat(independent.getConditionFlag(flag)).isEqualTo(1)

      machine.coprocessor1.setRegisterToFloat(12, 1.5f)
      machine.registerFile.initializeProgramCounter(pc)
      val add = InstrAddS()
      val statement = ProgramStatement(null, $$"add.s $f14,$f12,$f12", null, null, add, pc - 4, 1)
      intArrayOf(14, 12, 12).copyInto(checkNotNull(statement.operands))
      add.simulationCode.simulate(
          statement,
          InstructionContext(
              machine,
              machine.syscallEnvironment,
          ),
      )
      Assertions.assertThat(machine.coprocessor1.getFloatFromRegister(14)).isEqualTo(3.0f)
      Assertions.assertThat(backStepper.empty()).isFalse()
      backStepper.backStep()
      Assertions.assertThat(machine.coprocessor1.getValue(14)).isEqualTo(previousValues[14])

      for (initial in 0..1) {
        for (set in booleanArrayOf(true, false)) {
          // Whole-flag setters have historically bypassed undo recording.
          if (initial == 0) {
            machine.coprocessor1.clearConditionFlags()
          } else {
            machine.coprocessor1.setConditionFlags()
          }
          val flagsBefore = machine.coprocessor1.conditionFlags
          Assertions.assertThat(backStepper.empty()).isTrue()
          machine.registerFile.initializeProgramCounter(pc)
          val old =
              if (set) TestSessions.execution(machine).setConditionFlag(flag)
              else TestSessions.execution(machine).clearConditionFlag(flag)
          Assertions.assertThat(old).isEqualTo(initial)
          Assertions.assertThat(machine.coprocessor1.getConditionFlag(flag))
              .isEqualTo(if (set) 1 else 0)
          Assertions.assertThat(backStepper.empty()).isFalse()
          backStepper.backStep()
          Assertions.assertThat(machine.coprocessor1.conditionFlags).isEqualTo(flagsBefore)
          Assertions.assertThat(backStepper.empty()).isTrue()
        }
      }

      // Conversion helpers also retain their original behavior of bypassing undo recording.
      machine.coprocessor1.setRegisterToFloat($$"$f0", 1.5f)
      machine.coprocessor1.setRegisterToInt($$"$f2", 0x7fc12345)
      machine.coprocessor1.setRegisterPairToDouble($$"$f30", -2.5)
      Assertions.assertThat(machine.coprocessor1.getFloatFromRegister($$"$f0")).isEqualTo(1.5f)
      Assertions.assertThat(machine.coprocessor1.getIntFromRegister($$"$f2")).isEqualTo(0x7fc12345)
      Assertions.assertThat(machine.coprocessor1.getDoubleFromRegisterPair($$"$f30"))
          .isEqualTo(-2.5)
      Assertions.assertThat(backStepper.empty()).isTrue()
    } finally {
      for (i in 0..31) {
        machine.coprocessor1.setRegisterToInt(i, previousValues[i])
      }
      if ((previousFlags and 0xff.inv()) == 0) {
        machine.coprocessor1.clearConditionFlags()
      } else {
        machine.coprocessor1.setConditionFlags()
      }
      for (i in 0..7) {
        if ((previousFlags and (1 shl i)) == 0) {
          TestSessions.execution(machine).clearConditionFlag(i)
        } else {
          TestSessions.execution(machine).setConditionFlag(i)
        }
      }
      machine.registerFile.initializeProgramCounter(previousPc)
    }
  }
}
