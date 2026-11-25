package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.basic.InstrAdd
import edu.missouristate.mars.mips.instructions.basic.InstrAddS
import edu.missouristate.mars.mips.instructions.basic.InstrAddiu
import edu.missouristate.mars.mips.instructions.basic.InstrAddu
import edu.missouristate.mars.mips.instructions.basic.InstrBc1t2
import edu.missouristate.mars.mips.instructions.basic.InstrBeq
import edu.missouristate.mars.mips.instructions.basic.InstrBne
import edu.missouristate.mars.mips.instructions.basic.InstrCLtS2
import edu.missouristate.mars.mips.instructions.basic.InstrCvtDS
import edu.missouristate.mars.mips.instructions.basic.InstrCvtSW
import edu.missouristate.mars.mips.instructions.basic.InstrEret
import edu.missouristate.mars.mips.instructions.basic.InstrJalr2
import edu.missouristate.mars.mips.instructions.basic.InstrLw
import edu.missouristate.mars.mips.instructions.basic.InstrMfc0
import edu.missouristate.mars.mips.instructions.basic.InstrMflo
import edu.missouristate.mars.mips.instructions.basic.InstrMtc0
import edu.missouristate.mars.mips.instructions.basic.InstrMtc1
import edu.missouristate.mars.mips.instructions.basic.InstrMult
import edu.missouristate.mars.mips.instructions.basic.InstrSyscall
import edu.missouristate.mars.mips.instructions.syscalls.AbstractSyscall
import edu.missouristate.mars.simulator.Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION
import edu.missouristate.mars.testing.isInstanceOf
import edu.missouristate.mars.util.javaClassOf
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class InstructionContextTest {
  @Test
  fun interleavedProgramsReuseInstructionsWithoutSharingRegisterState() {
    val first: InstructionContext = context(false, RecordingEnvironment())
    val second: InstructionContext = context(false, RecordingEnvironment())
    first.registerFile.setValue(4, 2)
    second.registerFile.setValue(4, 5)
    val code: List<ProgramStatement> =
        listOf(
            statement(InstrAddu(), 8, 4, 0),
            statement(InstrAddiu(), 9, 8, 3),
            statement(InstrAddiu(), 8, 8, 1),
            statement(InstrBne(), 8, 9, -2),
            statement(InstrMult(), 8, 9),
            statement(InstrMflo(), 10),
            statement(InstrMtc1(), 10, 0),
            statement(InstrCvtSW(), 2, 0),
        )
    first.registerFile.initializeProgramCounter(BASE)
    second.registerFile.initializeProgramCounter(BASE)
    for (step in 0..29) {
      stepProgram(first, code)
      stepProgram(second, code)
    }
    Assertions.assertThat(first.registerFile.programCounter).isEqualTo(BASE + code.size * 4)
    Assertions.assertThat(second.registerFile.programCounter).isEqualTo(BASE + code.size * 4)
    Assertions.assertThat(first.registerFile.getValue(8)).isEqualTo(5)
    Assertions.assertThat(second.registerFile.getValue(8)).isEqualTo(8)
    Assertions.assertThat(first.registerFile.getValue(10)).isEqualTo(25)
    Assertions.assertThat(second.registerFile.getValue(10)).isEqualTo(64)
    Assertions.assertThat(first.coprocessor1.getFloatFromRegister(2)).isEqualTo(25.0f)
    Assertions.assertThat(second.coprocessor1.getFloatFromRegister(2)).isEqualTo(64.0f)
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun jumpAndLinkUseTheContextsDelaySetting(delayed: Boolean) {
    val context: InstructionContext = context(delayed, RecordingEnvironment())
    context.registerFile.initializeProgramCounter(BASE + 4)
    context.registerFile.setValue(8, BASE + 0x100)
    execute(InstrJalr2(), context, 8)
    Assertions.assertThat(context.registerFile.getValue(31))
        .isEqualTo(BASE + (if (delayed) 8 else 4))
    if (delayed) {
      Assertions.assertThat(context.registerFile.programCounter).isEqualTo(BASE + 4)
      context.completeInstruction()
      Assertions.assertThat(context.delayedBranch.isTriggered).isTrue()
      context.registerFile.incrementPC()
      execute(InstrAddiu(), context, 9, 0, 42)
      context.completeInstruction()
      Assertions.assertThat(context.registerFile.getValue(9)).isEqualTo(42)
      Assertions.assertThat(context.delayedBranch.isTriggered).isFalse()
    }
    Assertions.assertThat(context.registerFile.programCounter).isEqualTo(BASE + 0x100)
  }

  @Test
  fun pendingBranchesBelongToTheirOwnSessionsAndKeepTheOriginalTarget() {
    val first: InstructionContext = context(true, RecordingEnvironment())
    val second: InstructionContext = context(true, RecordingEnvironment())
    first.registerFile.initializeProgramCounter(BASE + 4)
    second.registerFile.initializeProgramCounter(BASE + 4)
    execute(InstrBeq(), first, 0, 0, 10)
    execute(InstrBeq(), second, 0, 0, 20)
    first.completeInstruction()
    second.completeInstruction()
    // A branch in the delay slot extends the slot without replacing the original target.
    execute(InstrBeq(), first, 0, 0, 30)
    first.completeInstruction()
    Assertions.assertThat(first.registerFile.programCounter).isEqualTo(BASE + 4)
    second.completeInstruction()
    Assertions.assertThat(second.registerFile.programCounter).isEqualTo(BASE + 84)
    first.completeInstruction()
    Assertions.assertThat(first.registerFile.programCounter).isEqualTo(BASE + 44)
  }

  @Test
  fun floatingPointComparisonBranchAndExceptionReturnUseOnlyTheirContext() {
    val first: InstructionContext = context(false, RecordingEnvironment())
    val second: InstructionContext = context(false, RecordingEnvironment())
    first.registerFile.initializeProgramCounter(BASE + 4)
    first.coprocessor1.setRegisterToFloat(0, 1.5f)
    first.coprocessor1.setRegisterToFloat(2, 2.5f)
    execute(InstrAddS(), first, 4, 0, 2)
    execute(InstrCvtDS(), first, 6, 4)
    execute(InstrCLtS2(), first, 7, 0, 2)
    execute(InstrBc1t2(), first, 7, 3)
    Assertions.assertThat(first.coprocessor1.getFloatFromRegister(4)).isEqualTo(4.0f)
    Assertions.assertThat(first.coprocessor1.getDoubleFromRegisterPair(6)).isEqualTo(4.0)
    Assertions.assertThat(first.registerFile.programCounter).isEqualTo(BASE + 16)
    Assertions.assertThat(second.coprocessor1.getValue(4)).isZero()
    Assertions.assertThat(second.coprocessor1.getConditionFlag(7)).isZero()
    first.coprocessor0.updateRegister(Coprocessor0State.EPC, BASE + 64)
    first.coprocessor0.updateRegister(Coprocessor0State.STATUS, 0xff13)
    execute(InstrEret(), first)
    Assertions.assertThat(first.registerFile.programCounter).isEqualTo(BASE + 64)
    Assertions.assertThat(first.coprocessor0.getValue(Coprocessor0State.STATUS)).isEqualTo(0xff11)
    execute(InstrMfc0(), first, 8, Coprocessor0State.EPC)
    execute(InstrMtc0(), first, 8, Coprocessor0State.VADDR)
    Assertions.assertThat(first.coprocessor0.getValue(Coprocessor0State.VADDR)).isEqualTo(BASE + 64)
    Assertions.assertThat(second.coprocessor0.getValue(Coprocessor0State.VADDR)).isZero()
  }

  @Test
  fun overflowAndAddressFaultsUpdateOnlyTheSuppliedExceptionRegisters() {
    val context: InstructionContext = context(false, RecordingEnvironment())
    context.registerFile.initializeProgramCounter(BASE + 0x104)
    context.registerFile.setValue(8, Int.MAX_VALUE)
    context.registerFile.setValue(9, 1)
    Assertions.assertThatThrownBy { execute(InstrAdd(), context, 10, 8, 9) }
        .isInstanceOfSatisfying<ProcessingException?>(
            javaClassOf<ProcessingException>(),
        ) { error ->
          Assertions.assertThat(
                  checkNotNull(error!!.errors()).generateErrorReport(),
              )
              .contains("0x00400100", "arithmetic overflow")
        }
    Assertions.assertThat(context.coprocessor0.getValue(Coprocessor0State.CAUSE))
        .isEqualTo(ARITHMETIC_OVERFLOW_EXCEPTION shl 2)
    Assertions.assertThat(context.coprocessor0.getValue(Coprocessor0State.EPC))
        .isEqualTo(BASE + 0x100)
    Assertions.assertThat(context.coprocessor0.getValue(Coprocessor0State.STATUS) and 2)
        .isEqualTo(2)
    Assertions.assertThatThrownBy { execute(InstrLw(), context, 10, 3, 0) }
        .isInstanceOf<ProcessingException>()
    Assertions.assertThat(context.coprocessor0.getValue(Coprocessor0State.VADDR)).isEqualTo(3)
  }

  @Test
  fun sharedSyscallImplementationsUseTheInvocationsRegistersAndEnvironment() {
    val firstIo = RecordingEnvironment()
    val secondIo = RecordingEnvironment()
    val first: InstructionContext = context(false, firstIo)
    val second: InstructionContext = context(false, secondIo)
    val syscall = InstrSyscall()
    first.registerFile.setValue(2, 1)
    first.registerFile.setValue(4, 42)
    second.registerFile.setValue(2, 1)
    second.registerFile.setValue(4, 99)
    execute(syscall, first)
    execute(syscall, second)
    Assertions.assertThat(firstIo.printed).containsExactly(42)
    Assertions.assertThat(secondIo.printed).containsExactly(99)
    firstIo.input = 123
    secondIo.input = 456
    first.registerFile.setValue(2, 5)
    second.registerFile.setValue(2, 5)
    execute(syscall, first)
    execute(syscall, second)
    Assertions.assertThat(first.registerFile.getValue(2)).isEqualTo(123)
    Assertions.assertThat(second.registerFile.getValue(2)).isEqualTo(456)
    first.registerFile.setValue(2, 17)
    first.registerFile.setValue(4, 7)
    Assertions.assertThatThrownBy { execute(syscall, first) }
        .isInstanceOfSatisfying<ProcessingException?>(
            javaClassOf<ProcessingException>(),
        ) { error ->
          Assertions.assertThat(error!!.errors()).isNull()
        }
    Assertions.assertThat(first.exitCode).isEqualTo(7)
    Assertions.assertThat(firstIo.exitCode).isEqualTo(7)
    Assertions.assertThat(second.exitCode).isZero()
  }

  @Test
  fun customInstructionAndSyscallReceiveExecutionContexts() {
    val calls = AtomicInteger()
    val instruction =
        BasicInstruction(
            "custom",
            BasicInstructionFormat.R_FORMAT,
            "00000000000000000000000000000000",
        ) { _, _ ->
          calls.incrementAndGet()
        }
    val syscall: AbstractSyscall =
        object : AbstractSyscall(123, "Legacy") {
          override fun simulate(statement: ProgramStatement?, context: InstructionContext) {
            calls.incrementAndGet()
          }
        }
    val context: InstructionContext = context(false, RecordingEnvironment())
    val statement: ProgramStatement = statement(instruction)
    instruction.simulationCode.simulate(statement, context)
    syscall.simulate(statement, context)
    Assertions.assertThat(calls.get()).isEqualTo(2)
  }

  private class RecordingEnvironment : TestEnvironment() {
    val printed: MutableList<Int?> = ArrayList()
    var input: Int = 0
    var exitCode: Int = 0

    override fun printInt(value: Int) {
      printed.add(value)
    }

    override fun readInt(): Int {
      return input
    }

    override fun exit(code: Int) {
      exitCode = code
    }
  }

  companion object {
    private const val BASE = 0x00400000

    private fun context(
        delayed: Boolean,
        environment: RecordingEnvironment,
    ): InstructionContext {
      val settings = Settings()
      settings.simulationSettings.setDelayedBranchingEnabled(delayed)
      val session =
          MarsComponent.build {
                coreModule(CoreModule(settings, target = MarsTarget.MARS32))
                guiProxy(TestSessions.NO_GUI)
              }
              .context()
      return InstructionContext(session, environment)
    }

    private fun statement(
        instruction: BasicInstruction,
        vararg operands: Int,
    ): ProgramStatement {
      val statement = ProgramStatement(null, "test", null, null, instruction, BASE, 1)
      operands.copyInto(checkNotNull(statement.operands))
      return statement
    }

    @Throws(ProcessingException::class)
    private fun execute(
        instruction: BasicInstruction,
        context: InstructionContext,
        vararg operands: Int,
    ) {
      instruction.simulationCode.simulate(statement(instruction, *operands), context)
    }

    @Throws(ProcessingException::class)
    private fun stepProgram(context: InstructionContext, code: List<ProgramStatement>) {
      val index: Int = (context.registerFile.programCounter - BASE) / 4
      if (index == code.size) {
        return
      }
      Assertions.assertThat(index).isBetween(0, code.size - 1)
      val statement = code[index]
      context.registerFile.incrementPC()
      (checkNotNull(statement.instruction) as BasicInstruction)
          .simulationCode
          .simulate(statement, context)
      context.completeInstruction()
    }
  }
}
