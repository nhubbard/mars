package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionCallSetupTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()

  private fun image(
      body: String = "lbu \$v0,0(\$a0)\nlbu \$t1,1(\$a0)\nsll \$t1,\$t1,8\nor \$v0,\$v0,\$t1",
      wide: Boolean = false,
      littleEndian: Boolean = true,
  ) =
      session().use { machine ->
        machine.memory.byteOrder = littleEndian
        val path = directory.resolve("call-setup.asm")
        val source =
            ".data ${if (wide) "0x100010000" else "0x10010000"}\ninput: .byte 165:8\nhidden: .word 0\n" +
                ".text\nmain: li \$v0,10\nsyscall\nreturned: nop\nfunction:\n$body\njrc \$ra\n"
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun call(bytes: String = "1234", value: Long = 0x3412) =
      FunctionCallCheck(
          "function",
          "returned",
          emptyMap(),
          mapOf("v0" to value),
          argumentAddresses = mapOf("a0" to FunctionDataAddress("input", 1)),
          setupMemory = listOf(FunctionMemoryCheck("input", bytes, 1)),
          memory = listOf(FunctionMemoryCheck("input", "a5${bytes}a5a5a5a5a5")),
          accessPolicies =
              listOf(
                  FunctionAccessPolicyCheck(
                      FunctionDataAddress("input"),
                      FunctionDataAddress("input", 8),
                      readable = listOf(FunctionDataWindow(FunctionDataAddress("input", 1), 2)),
                  ),
              ),
          accessHistory =
              FunctionAccessHistoryCheck(
                  listOf(FunctionDataWindow(FunctionDataAddress("input"), 8)),
                  2,
              ),
      )

  private fun contract(
      calls: List<FunctionCallCheck> = listOf(call(), call("ff80", 0x80ff), call()),
  ) =
      FunctionCheckCase(
          "changing input in one session",
          calls,
          preservedRegisters = listOf("s0"),
          initialRegisters = mapOf("s0" to 0x1234567800000000L),
          noStores = listOf(FunctionNoStoreCheck("input", 8)),
      )

  @Test
  fun `unaligned setup relocates preserves surrounding bytes and stays outside execution histories`() {
    for (wide in listOf(false, true)) for (littleEndian in listOf(false, true)) {
      val result =
          PreparedFunctionChecks.evaluate(
                  image(wide = wide, littleEndian = littleEndian),
                  listOf(contract()),
                  ::session,
              )
              .single()
      assertTrue(result.passed, result.failure)
    }
  }

  @Test
  fun `fixture changes do not reset hidden machine state or excuse simulated stores`() {
    val cache =
        "dla \$t0,hidden\nlw \$v0,0(\$t0)\nbnezc \$v0,cached\nlbu \$v0,0(\$a0)\nsw \$v0,0(\$t0)\ncached: nop"
    val singleByte =
        contract(
            listOf(
                call().copy(expectedRegisters = mapOf("v0" to 0x12L)),
                call("ff80", 0xff),
            ),
        )
    val stale =
        PreparedFunctionChecks.evaluate(image(cache), listOf(singleByte), ::session).single()
    assertFalse(stale.passed)
    assertTrue(stale.failure!!.contains("Call 2"), stale.failure)
    val store =
        PreparedFunctionChecks.evaluate(
                image("sb \$zero,0(\$a0)\nli \$v0,0"),
                listOf(
                    contract(
                        listOf(
                            call()
                                .copy(expectedRegisters = mapOf("v0" to 0L), memory = emptyList()),
                        ),
                    ),
                ),
                ::session,
            )
            .single()
    assertFalse(store.passed)
    assertTrue(store.failure!!.contains("stores"), store.failure)
  }

  @Test
  fun `overlapping fixture ranges apply in declared order without refreshing caller stack guards`() {
    val ordered =
        call()
            .copy(
                setupMemory =
                    listOf(
                        FunctionMemoryCheck("input", "aa1122"),
                        FunctionMemoryCheck("input", "fe", 1),
                    ),
                expectedRegisters = mapOf("v0" to 0x22feL),
                memory = listOf(FunctionMemoryCheck("input", "aafe22a5a5a5a5a5")),
            )
    assertTrue(
        PreparedFunctionChecks.evaluate(image(), listOf(contract(listOf(ordered))), ::session)
            .single()
            .passed,
    )
    val guarded =
        contract(listOf(call()))
            .copy(
                initialRegisterAddresses = mapOf("sp" to FunctionDataAddress("input")),
                callerStackBytes = 8,
            )
    val changed = PreparedFunctionChecks.evaluate(image(), listOf(guarded), ::session).single()
    assertFalse(changed.passed)
    assertTrue(changed.failure!!.contains("Caller-owned stack"), changed.failure)
  }

  @Test
  fun `setup ranges are copied and revalidated before opening any session`() {
    val ranges = call().setupMemory.toMutableList()
    val case = contract(listOf(call().copy(setupMemory = ranges)))
    assertTrue(
        PreparedFunctionChecks.evaluate(
                image(),
                listOf(case),
                {
                  ranges.clear()
                  session()
                },
            )
            .single()
            .passed,
    )
    ranges.addAll(List(65) { FunctionMemoryCheck("input", "00") })
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image(),
          listOf(case),
          { error("Invalid setup must be rejected before machine acquisition") },
      )
    }
  }

  @Test
  fun `setup memory contributes to aggregate case capacity and range limits`() {
    assertThrows<IllegalArgumentException> {
      call().copy(setupMemory = List(65) { FunctionMemoryCheck("input", "00") })
    }
    val large = FunctionMemoryCheck("input", "00".repeat(524289))
    assertThrows<IllegalArgumentException> {
      contract(
          listOf(
              call().copy(setupMemory = listOf(large)),
              call().copy(setupMemory = listOf(large)),
          ),
      )
    }
  }

  @Test
  fun `unresolved or invalid setup fails its invocation and releases the owned session`() {
    for (range in
        listOf(FunctionMemoryCheck("absent", "00"), FunctionMemoryCheck("input", "00", -1048576))) {
      val machines = mutableListOf<MarsContext>()
      val result =
          PreparedFunctionChecks.evaluate(
                  image(),
                  listOf(contract(listOf(call().copy(setupMemory = listOf(range))))),
                  {
                    session().also(machines::add)
                  },
              )
              .single()
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains("Call 1"), result.failure)
      machines.forEach { assertThrows<IllegalStateException> { it.claimStoppedSession() } }
    }
  }

  @Test
  fun `byte preparation is available at a live pause but rejects terminal execution`() {
    session().use { machine ->
      machine.loadAssemblyImage(image()) {}
      val program = checkNotNull(machine.program)
      AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { execution ->
        execution.setBytes("input", 1, 2, offset = 1)
        execution.setRegisterToDataAddress("a0", "input", 1)
        execution.callFunction("function", "returned")
        execution.setBytes("input", 3, 4, offset = 1)
        execution.callFunction("function", "returned")
        execution.expectRegister("v0", 0x0403)
        execution.enterFunction("main", "returned")
        execution.runToExit()
        assertThrows<CaseFailure> { execution.setBytes("input", 99, offset = 1) }
        execution.expectBytes("input", 165, 3, 4, 165, 165, 165, 165, 165)
      }
    }
  }

  @Test
  fun `instruction allowance remains cumulative across fixture changes`() {
    val spin = "li \$t0,12\nagain: daddiu \$t0,\$t0,-1\nbnezc \$t0,again\nli \$v0,0"
    val invocation = call().copy(expectedRegisters = mapOf("v0" to 0L), memory = emptyList())
    val once = contract(listOf(invocation)).copy(instructionLimit = 40)
    assertTrue(
        PreparedFunctionChecks.evaluate(image(spin), listOf(once), ::session).single().passed,
    )
    val twice =
        PreparedFunctionChecks.evaluate(
                image(spin),
                listOf(once.copy(calls = listOf(invocation, invocation))),
                ::session,
            )
            .single()
    assertFalse(twice.passed)
    assertTrue(twice.failure!!.contains("limit", ignoreCase = true), twice.failure)
  }
}
