package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.MarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionAddressSetupTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()

  private fun image(
      wide: Boolean = false,
      little: Boolean = false,
      body: String = $$"ld $v0,0($a0)",
  ) =
      session().use { machine ->
        machine.memory.byteOrder = little
        val source =
            ".data ${if (wide) "0x100010000" else "0x10010000"}\ncontext: .byte 165:24\ninput: .byte 19:16\n" +
                $$".text\nmain: li $v0,10\nsyscall\nreturned: nop\nfunction:\n$$body\njrc $ra\n"
        val path = directory.resolve("pointers.asm")
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun pointer(offset: Int = 3) =
      FunctionAddressMemoryCheck(
          FunctionDataAddress("context", 8),
          FunctionDataAddress("input", offset),
      )

  private fun call(offset: Int = 3) =
      FunctionCallCheck(
          "function",
          "returned",
          emptyMap(),
          emptyMap(),
          argumentAddresses = mapOf("a0" to FunctionDataAddress("context", 8)),
          expectedAddresses = mapOf("v0" to FunctionDataAddress("input", offset)),
          setupMemory = listOf(FunctionMemoryCheck("context", "a5", repeat = 24)),
          setupAddressMemory = listOf(pointer(offset)),
          addressMemory = listOf(pointer(offset)),
          callerAccess =
              FunctionCallerAccessCheck(
                  listOf(FunctionDataWindow(FunctionDataAddress("context"), 24)),
                  readable = listOf(FunctionDataWindow(FunctionDataAddress("context", 8), 8)),
                  maxAccesses = 2,
              ),
      )

  private fun contract(calls: List<FunctionCallCheck> = listOf(call(), call(7))) =
      FunctionCheckCase("relocated context pointers", calls, callerStackBytes = 0)

  @Test
  fun `native pointer fixtures follow bytes relocate in both orders and remain outside callee history`() {
    for (wide in listOf(false, true)) for (little in listOf(false, true)) {
      val result =
          PreparedFunctionChecks.evaluate(image(wide, little), listOf(contract()), ::session)
              .single()
      assertTrue(result.passed, result.failure)
    }
  }

  @Test
  fun `declared pointer order permits overlays and unaligned destinations`() {
    val last = pointer(7).copy(at = FunctionDataAddress("context", 9))
    val invocation =
        call(7)
            .copy(
                argumentAddresses = mapOf("a0" to FunctionDataAddress("context", 9)),
                setupAddressMemory =
                    listOf(last.copy(value = FunctionDataAddress("input", 1)), last),
                addressMemory = listOf(last),
                callerAccess = null,
            )
    // Reconstruct the unaligned field with byte loads, independently of target byte order.
    val body =
        (0..7).joinToString("\n") { index ->
          $$"lbu $t0,$$index($a0)\n" +
              (if (index == 0) $$"move $v0,$t0" else $$"dsll $v0,$v0,8\nor $v0,$v0,$t0")
        }
    assertTrue(
        PreparedFunctionChecks.evaluate(
                image(body = body),
                listOf(contract(listOf(invocation))),
                ::session,
            )
            .single()
            .passed,
    )
  }

  @Test
  fun `pointer fixtures cannot excuse callee stores or refresh a protected caller stack`() {
    val store =
        PreparedFunctionChecks.evaluate(
                image(body = $$"ld $v0,0($a0)\nsd $v0,0($a0)"),
                listOf(contract(listOf(call()))),
                ::session,
            )
            .single()
    assertFalse(store.passed)
    val stack =
        contract(listOf(call()))
            .copy(
                initialRegisterAddresses = mapOf("sp" to FunctionDataAddress("context")),
                callerStackBytes = 24,
            )
    val changed = PreparedFunctionChecks.evaluate(image(), listOf(stack), ::session).single()
    assertFalse(changed.passed)
    assertTrue(changed.failure!!.contains("Caller-owned stack"), changed.failure)
  }

  @Test
  fun `mutable pointer lists are captured and revalidated before session acquisition`() {
    val pointers = mutableListOf(pointer())
    val case = contract(listOf(call().copy(setupAddressMemory = pointers)))
    assertTrue(
        PreparedFunctionChecks.evaluate(
                image(),
                listOf(case),
                {
                  pointers.clear()
                  session()
                },
            )
            .single()
            .passed,
    )
    pointers.addAll(List(65) { pointer() })
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image(),
          listOf(case),
          { error("Invalid pointer setup must fail before session acquisition") },
      )
    }
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `pointer fixtures share byte range and aggregate case budgets`() {
    assertThrows<IllegalArgumentException> {
      call().copy(setupMemory = List(64) { FunctionMemoryCheck("context", "00") })
    }
    assertThrows<IllegalArgumentException> {
      call().copy(setupMemory = listOf(FunctionMemoryCheck("input", "00", repeat = 1048576)))
    }
    val large =
        call().copy(setupMemory = listOf(FunctionMemoryCheck("input", "00", repeat = 524284)))
    assertThrows<IllegalArgumentException> { contract(listOf(large, large)) }
  }

  @Test
  fun `invalid pointer source or destination fails the invocation and closes the session`() {
    for (bad in
        listOf(
            pointer().copy(value = FunctionDataAddress("missing")),
            pointer().copy(at = FunctionDataAddress("missing")),
            pointer().copy(at = FunctionDataAddress("context", -1048576)),
        )) {
      val machines = mutableListOf<MarsContext>()
      val result =
          PreparedFunctionChecks.evaluate(
                  image(),
                  listOf(contract(listOf(call().copy(setupAddressMemory = listOf(bad))))),
                  { session().also(machines::add) },
              )
              .single()
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains("Call 1"), result.failure)
      machines.forEach { assertThrows<IllegalStateException> { it.claimStoppedSession() } }
    }
  }
}
