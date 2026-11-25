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

class PreparedFunctionInitialAddressTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()

  private fun image(body: String, wide: Boolean = false, padding: Int = 0) =
      session().use { machine ->
        val path = directory.resolve("initial-address.asm")
        val source =
            ".data ${if (wide) "0x100010000" else "0x10010000"}\n.space $padding\n.align 4\nworkspace: .space 256\n" +
                $$"caller: .byte 11:32\ninput: .byte 1,2,3,4\n.text\nmain: li $v0,10\nsyscall\nreturned: nop\nfunction:\n$$body\njrc $ra\n"
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun contract(
      addresses: Map<String, FunctionDataAddress> =
          mapOf(
              "sp" to FunctionDataAddress("workspace", 256),
              "s0" to FunctionDataAddress("input", 3),
          ),
  ) =
      FunctionCheckCase(
          "assembled initial values",
          listOf(
              FunctionCallCheck(
                  "function",
                  "returned",
                  emptyMap(),
                  emptyMap(),
                  expectedAddresses =
                      mapOf(
                          "v0" to FunctionDataAddress("input", 3),
                          "sp" to FunctionDataAddress("workspace", 256),
                      ),
              ),
          ),
          preservedRegisters = listOf("s0"),
          initialRegisterAddresses = addresses,
      )

  private val body =
      $$"daddiu $sp,$sp,-16\nsd $s0,0($sp)\nld $s0,0($sp)\ndaddiu $sp,$sp,16\nmove $v0,$s0"

  @Test
  fun `initial pointers and stack follow assembled layout including full-width relocation`() {
    for (wide in listOf(false, true)) for (padding in listOf(0, 260)) {
      val result =
          PreparedFunctionChecks.evaluate(image(body, wide, padding), listOf(contract()), ::session)
              .single()
      assertTrue(result.passed, result.failure)
    }
  }

  @Test
  fun `symbolic stack is aligned before snapshot and private frame traffic stays independent of caller bytes`() {
    val addresses =
        mapOf(
            "sp" to FunctionDataAddress("workspace", 263),
            "s0" to FunctionDataAddress("input", 3),
        )
    assertTrue(
        PreparedFunctionChecks.evaluate(image(body), listOf(contract(addresses)), ::session)
            .single()
            .passed,
    )
    val changed =
        PreparedFunctionChecks.evaluate(
                image($$"sb $zero,0($sp)\n$$body"),
                listOf(contract()),
                ::session,
            )
            .single()
    assertFalse(changed.passed)
    assertTrue(checkNotNull(changed.failure).contains("stack"), changed.failure)
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `integer and address initialization reject collisions aliases zero and unknown registers`() {
    assertThrows<IllegalArgumentException> {
      contract().copy(initialRegisters = mapOf("sp" to 123L))
    }
    assertThrows<IllegalArgumentException> {
      contract().copy(initialRegisters = mapOf($$"$sp" to 123L))
    }
    assertThrows<IllegalArgumentException> {
      contract(
          mapOf(
              "sp" to FunctionDataAddress("workspace"),
              $$"$sp" to FunctionDataAddress("workspace"),
          ),
      )
    }
    assertThrows<IllegalArgumentException> {
      contract(mapOf("zero" to FunctionDataAddress("input")))
    }
    assertThrows<IllegalArgumentException> {
      contract(mapOf("not_a_register" to FunctionDataAddress("input")))
    }
  }

  @Test
  fun `caller-owned address map is captured and revalidated before any session opens`() {
    val addresses = contract().initialRegisterAddresses.toMutableMap()
    val case = contract(addresses)
    assertTrue(
        PreparedFunctionChecks.evaluate(
                image(body),
                listOf(case),
                {
                  addresses.clear()
                  session()
                },
            )
            .single()
            .passed,
    )
    addresses["zero"] = FunctionDataAddress("input")
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image(body),
          listOf(case),
          { error("Invalid setup must not open a session") },
      )
    }
  }

  @Test
  fun `unresolved initial data address fails its case and closes the fresh machine`() {
    val machines = mutableListOf<MarsContext>()
    val result =
        PreparedFunctionChecks.evaluate(
                image(body),
                listOf(contract(mapOf("sp" to FunctionDataAddress("missing")))),
                {
                  session().also(machines::add)
                },
            )
            .single()
    assertFalse(result.passed)
    assertTrue(checkNotNull(result.failure).contains("missing"), result.failure)
    machines.forEach { assertThrows<IllegalStateException> { it.claimStoppedSession() } }
  }
}
