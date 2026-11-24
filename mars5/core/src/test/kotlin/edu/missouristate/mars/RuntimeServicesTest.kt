package edu.missouristate.mars

import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.api.SyscallEnvironment
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.core.SessionAssembler
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.memory.TextMemorySegment
import edu.missouristate.mars.mips.hardware.memory.WordMemorySegment
import edu.missouristate.mars.mips.instructions.syscalls.SyscallMidiOut
import edu.missouristate.mars.mips.instructions.syscalls.SyscallMidiOutSync
import edu.missouristate.mars.simulator.Exceptions.EXTERNAL_INTERRUPT_KEYBOARD
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class RuntimeServicesTest {
  @TempDir lateinit var directory: Path

  @Test
  fun pendingInterruptsAndHandlersBelongOnlyToTheirSession() {
    val source = directory.resolve("interrupt.s")
    Files.writeString(
        source,
        $$"""
        .text
        addiu $t0,$zero,42
        li $v0,10
        syscall
        .ktext 0x80000180
        mfc0 $s0,$13
        eret

        """
            .trimIndent(),
    )
    TestSessions.create().use { first ->
      TestSessions.create().use { second ->
        first.assemble(listOf(source))
        second.assemble(listOf(source))
        first.requestExternalInterrupt(EXTERNAL_INTERRUPT_KEYBOARD)
        val a: CompletableFuture<StopEvent> = first.simulator().runAsync(0, null)
        val b: CompletableFuture<StopEvent> = second.simulator().runAsync(0, null)
        Assertions.assertThat<StopEvent.Reason?>(a.get(5, TimeUnit.SECONDS)!!.reason)
            .isEqualTo(StopEvent.Reason.NORMAL_EXIT)
        Assertions.assertThat<StopEvent.Reason?>(b.get(5, TimeUnit.SECONDS)!!.reason)
            .isEqualTo(StopEvent.Reason.NORMAL_EXIT)
        Assertions.assertThat(first.registerFile.getValue(16))
            .isEqualTo(EXTERNAL_INTERRUPT_KEYBOARD shl 2)
        Assertions.assertThat(second.registerFile.getValue(16)).isZero()
        Assertions.assertThat(first.registerFile.getValue(8)).isEqualTo(42)
        Assertions.assertThat(first.coprocessor0.getValue(Coprocessor0State.STATUS) and 2).isZero()
      }
    }
  }

  @Test
  fun midiSyscallsDispatchNormalizedValuesToTheirSuppliedEnvironment() {
    TestSessions.create().use { first ->
      TestSessions.create().use { second ->
        val a = ArrayList<List<Any?>>()
        val b = ArrayList<List<Any?>>()
        val left = InstructionContext(first, midiRecorder(a))
        val right = InstructionContext(second, midiRecorder(b))
        first.registerFile.updateRegister(4, -1)
        first.registerFile.updateRegister(5, -1)
        first.registerFile.updateRegister(6, 128)
        first.registerFile.updateRegister(7, 128)
        SyscallMidiOut().simulate(null, left)
        second.registerFile.updateRegister(4, 64)
        second.registerFile.updateRegister(5, 10)
        second.registerFile.updateRegister(6, 3)
        second.registerFile.updateRegister(7, 20)
        SyscallMidiOutSync().simulate(null, right)
        Assertions.assertThat(a).containsExactly(mutableListOf<Any?>(60, 1000, 0, 100, false))
        Assertions.assertThat(b).containsExactly(mutableListOf<Any?>(64, 10, 3, 20, true))
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun sparseStoragePreservesByteOrderAndCrossBlockWrites(littleEndian: Boolean) {
    val segment = WordMemorySegment(2, false) { littleEndian }
    Assertions.assertThat(segment.fetchWordOrNull(0)).isNull()
    Assertions.assertThat(segment.storeWord(0, 0x12345678)).isZero()
    Assertions.assertThat(segment.fetchBytes(0, 1)).isEqualTo(if (littleEndian) 0x78 else 0x12)
    segment.storeBytes(4095, 2, 0xabcd)
    Assertions.assertThat(segment.fetchBytes(4095, 2)).isEqualTo(0xabcd)
    Assertions.assertThat(segment.fetchBytes(4095, 1)).isEqualTo(if (littleEndian) 0xcd else 0xab)
    Assertions.assertThat(segment.fetchBytes(4096, 1)).isEqualTo(if (littleEndian) 0xab else 0xcd)
    Assertions.assertThat(segment.fetchWordOrNull(1024)).isNotNull()
    Assertions.assertThat(segment.storeWord(1024, 42))
        .isEqualTo(if (littleEndian) 0xab else 0xcd000000.toInt())
    Assertions.assertThat(segment.fetchWord(1024)).isEqualTo(42)
  }

  @Test
  fun textStorageKeepsAllocatedBlocksIndependent() {
    val text = TextMemorySegment(2)
    val first = ProgramStatement(0, 0)
    val second = ProgramStatement(0, 4096)
    Assertions.assertThat<ProgramStatement>(text.read(0)).isNull()
    text.store(0, first)
    text.store(1024, second)
    Assertions.assertThat<ProgramStatement>(text.read(0)).isSameAs(first)
    Assertions.assertThat<ProgramStatement>(text.read(1024)).isSameAs(second)
    Assertions.assertThat<ProgramStatement>(text.read(1)).isNull()
    Assertions.assertThat<ProgramStatement>(text.read(2048)).isNull()
  }

  @Test
  fun successfulAssemblyRetainsWarningsAndWarningPolicyIsExplicit() {
    val source = directory.resolve("warnings.s")
    Files.writeString(source, ".text\n.unsupported\nnop\n")
    TestSessions.create().use { session ->
      val assembler = SessionAssembler(session)
      val success =
          assembler.assemble(
              listOf(source),
              null,
              extendedAssemblerEnabled = true,
              warningsAreErrors = false,
          )
      Assertions.assertThat(success.succeeded()).isTrue()
      Assertions.assertThat(success.errors()).hasSize(1).allMatch(AssemblyError::isWarning)
      Assertions.assertThat(success.program()).isNotNull()
      Assertions.assertThatThrownBy { (success.errors() as MutableList).clear() }
          .isInstanceOf(UnsupportedOperationException::class.java)
      val failure =
          assembler.assemble(
              listOf(source),
              null,
              extendedAssemblerEnabled = true,
              warningsAreErrors = true,
          )
      Assertions.assertThat(failure.succeeded()).isFalse()
      Assertions.assertThat(failure.program()).isNull()
    }
  }

  companion object {
    private fun midiRecorder(calls: MutableList<List<Any?>>): SyscallEnvironment {
      return Proxy.newProxyInstance(
          SyscallEnvironment::class.java.classLoader,
          arrayOf<Class<*>>(SyscallEnvironment::class.java),
          InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
            if (method!!.name == "playTone") {
              calls.add(requireNotNull(args).toList())
              return@InvocationHandler null
            }
            throw AssertionError("Unexpected I/O: " + method.name)
          },
      ) as SyscallEnvironment
    }
  }
}
