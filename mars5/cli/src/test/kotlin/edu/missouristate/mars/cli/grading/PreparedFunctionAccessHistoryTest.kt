package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionAccessHistoryTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()

  private fun image(
      body: String,
      reordered: Boolean = false,
      wide: Boolean = false,
      gap: Int = 12,
  ) =
      session().use { machine ->
        val names = if (reordered) listOf("last", "first") else listOf("first", "last")
        val source =
            ".data ${if (wide) "0x100000000" else "0x10010000"}\n" +
                "${names[0]}: .word 11\ngap: .space $gap\n${names[1]}: .word 22\noutside: .word 7\n" +
                ".text\nmain: li \$v0,10\nsyscall\nreturned: bc main\nfunction:\n$body\nli \$v0,0\njrc \$ra\n"
        val path = directory.resolve("history.asm")
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun region(label: String, size: Int = 4) =
      FunctionDataWindow(FunctionDataAddress(label), size)

  private fun history(capacity: Int = 2) =
      FunctionAccessHistoryCheck(listOf(region("first"), region("last")), capacity)

  private fun call() =
      FunctionCallCheck(
          "function",
          "returned",
          emptyMap(),
          mapOf("v0" to 0L),
          accessHistory = history(),
      )

  private fun contract() = FunctionCheckCase("enclosing history", listOf(call()))

  private val paddingReads =
      "dla \$t0,gap\nlw \$t1,0(\$t0)\nlw \$t1,4(\$t0)\ndla \$t0,outside\nlw \$t1,0(\$t0)"

  @Test
  fun `enclosing history includes padding and counts only the assembled span under relocation and reordering`() {
    for (reordered in listOf(false, true)) for (wide in listOf(false, true)) {
      val image = image(paddingReads, reordered, wide)
      val pass = PreparedFunctionChecks.evaluate(image, listOf(contract()), ::session).single()
      assertTrue(pass.passed, pass.failure)
      val small = contract().copy(calls = listOf(call().copy(accessHistory = history(1))))
      val fail = PreparedFunctionChecks.evaluate(image, listOf(small), ::session).single()
      assertFalse(fail.passed)
      assertTrue(fail.failure!!.contains("1 notices"), fail.failure)
    }
  }

  @Test
  fun `history resets per invocation and does not grant read or write permission`() {
    val named =
        listOf("first", "last").map {
          FunctionAccessPolicyCheck(FunctionDataAddress(it), FunctionDataAddress(it, 4))
        }
    val call = call().copy(accessPolicies = named)
    val case = contract().copy(calls = listOf(call, call))
    val pass =
        PreparedFunctionChecks.evaluate(image(paddingReads), listOf(case), ::session).single()
    assertTrue(pass.passed, pass.failure)
    for (body in listOf("dla \$t0,first\nlw \$t1,0(\$t0)", "dla \$t0,first\nsw \$zero,0(\$t0)")) {
      val fail = PreparedFunctionChecks.evaluate(image(body), listOf(case), ::session).single()
      assertFalse(fail.passed)
      assertTrue(fail.failure!!.contains("outside its permitted caller windows"), fail.failure)
    }
  }

  @Test
  fun `regions and aggregate observation budgets are revalidated before machine acquisition`() {
    for (bad in listOf(0, -1, 1_000_001)) assertThrows<IllegalArgumentException> { history(bad) }
    assertThrows<IllegalArgumentException> { FunctionAccessHistoryCheck(emptyList()) }
    assertThrows<IllegalArgumentException> {
      FunctionAccessHistoryCheck(List(65) { region("first") })
    }
    assertThrows<IllegalArgumentException> {
      FunctionAccessHistoryCheck(listOf(region("first", 0)))
    }
    assertThrows<IllegalArgumentException> {
      FunctionAccessHistoryCheck(List(2) { region("first", 1_048_576) })
    }
    val policy =
        FunctionAccessPolicyCheck(FunctionDataAddress("first"), FunctionDataAddress("last"))
    call().copy(accessHistory = history(65_536), accessPolicies = List(63) { policy })
    assertThrows<IllegalArgumentException> {
      call().copy(accessHistory = history(65_536), accessPolicies = List(64) { policy })
    }
    val regions = history().regions.toMutableList()
    val case =
        contract()
            .copy(calls = listOf(call().copy(accessHistory = FunctionAccessHistoryCheck(regions))))
    regions.clear()
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image(paddingReads),
          listOf(case),
          { error("Reject mutable invalid data before opening a session") },
      )
    }
  }

  @Test
  fun `assembled enclosing gaps remain bounded even when declared object sizes are small`() {
    val result =
        PreparedFunctionChecks.evaluate(
                image("nop", gap = 1_048_576),
                listOf(contract()),
                ::session,
            )
            .single()
    assertFalse(result.passed)
    assertTrue(result.failure!!.contains("Enclosing data-access history span"), result.failure)
    val missing =
        contract()
            .copy(
                calls =
                    listOf(
                        call()
                            .copy(
                                accessHistory =
                                    FunctionAccessHistoryCheck(listOf(region("absent"))),
                            ),
                    ),
            )
    assertFalse(
        PreparedFunctionChecks.evaluate(image("nop"), listOf(missing), ::session).single().passed,
    )
  }

  @Test
  fun `six million budget executes beyond the previous ceiling and remains cumulative`() {
    val body = "li \$t0,1600001\nagain: daddiu \$t0,\$t0,-1\nbnezc \$t0,again"
    val image = image(body)
    val plain = call().copy(accessHistory = null)
    val old = contract().copy(calls = listOf(plain), instructionLimit = 3_000_000)
    val expanded = old.copy(name = "six million", instructionLimit = 6_000_000)
    val results = PreparedFunctionChecks.evaluate(image, listOf(old, expanded), ::session)
    assertFalse(results[0].passed)
    assertTrue(results[1].passed, results[1].failure)
    val cumulative =
        PreparedFunctionChecks.evaluate(
                image,
                listOf(expanded.copy(calls = listOf(plain, plain))),
                ::session,
            )
            .single()
    assertFalse(cumulative.passed)
  }
}
