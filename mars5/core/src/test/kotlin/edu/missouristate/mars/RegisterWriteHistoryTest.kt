package edu.missouristate.mars

import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile
import edu.missouristate.mars.simulator.BackStepper
import edu.missouristate.mars.simulator.BackStepper.RegisterWrite
import edu.missouristate.mars.simulator.BackStepper.RegisterWriteBank
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

internal class RegisterWriteHistoryTest {
    @Test
    fun newestWritingInstructionInEachBankIsRestoredAfterBackstep() {
        TestSessions.create().use { machine ->
            val history: BackStepper = machine.backStepper
            val execution = TestSessions.execution(machine)
            val pc: Int = machine.memory.textBaseAddress
            val statement = ProgramStatement(0, pc, machine.instructionSet)
            Assertions.assertThat<RegisterWrite?>(history.previousRegisterWrites()).isEmpty()

            history.beginInstruction(pc, statement)
            execution.updateRegister(8, 42)
            history.endInstruction()

            history.beginInstruction(pc, statement)
            execution.updateRegister(IntegerRegisterFile.HI_REGISTER, 0)
            execution.updateRegister(IntegerRegisterFile.LO_REGISTER, 6)
            execution.updateRegister(IntegerRegisterFile.LO_REGISTER, 6)
            history.endInstruction()

            history.beginInstruction(pc, statement)
            execution.updateCoprocessor0(Coprocessor0State.EPC, 123)
            execution.updateCoprocessor1(0, 1)
            execution.updateCoprocessor1(1, 2)
            execution.setConditionFlag(0)
            history.endInstruction()
            Assertions.assertThat<RegisterWrite?>(history.previousRegisterWrites())
                .containsExactlyInAnyOrder(
                    RegisterWrite(RegisterWriteBank.CPU, IntegerRegisterFile.HI_REGISTER),
                    RegisterWrite(RegisterWriteBank.CPU, IntegerRegisterFile.LO_REGISTER),
                    RegisterWrite(RegisterWriteBank.COPROCESSOR_0, Coprocessor0State.EPC),
                    RegisterWrite(RegisterWriteBank.COPROCESSOR_1, 0),
                    RegisterWrite(RegisterWriteBank.COPROCESSOR_1, 1),
                    RegisterWrite(RegisterWriteBank.CONDITION_FLAGS, 0),
                )

            history.backStep()
            Assertions.assertThat<RegisterWrite?>(history.previousRegisterWrites())
                .containsExactlyInAnyOrder(
                    RegisterWrite(RegisterWriteBank.CPU, IntegerRegisterFile.HI_REGISTER),
                    RegisterWrite(RegisterWriteBank.CPU, IntegerRegisterFile.LO_REGISTER),
                )
            history.backStep()
            Assertions.assertThat<RegisterWrite?>(history.previousRegisterWrites())
                .containsExactly(RegisterWrite(RegisterWriteBank.CPU, 8))

            execution.updateRegister(9, 7)
            Assertions.assertThat<RegisterWrite?>(history.previousRegisterWrites())
                .containsExactly(RegisterWrite(RegisterWriteBank.CPU, 8))
            history.setEnabled(false)
            Assertions.assertThat<RegisterWrite?>(history.previousRegisterWrites()).isEmpty()
            history.setEnabled(true)
            history.clear()
            Assertions.assertThat<RegisterWrite?>(history.previousRegisterWrites()).isEmpty()
        }
    }
}
