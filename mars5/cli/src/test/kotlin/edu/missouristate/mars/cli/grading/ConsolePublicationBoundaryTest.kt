package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ConsolePublicationBoundaryTest {
  @TempDir lateinit var directory: Path
  private val source =
      $$"""
      .data
      protected: .word 7
      end: .word 0
      .text
      .globl main
      main:
          dli $s0,0x1234567800000007
          dli $ra,0x1122334455667788
      entering:
          nop
          daddiu $s0,$s0,1
      returned:
          nop
          daddiu $s0,$s0,1
      done:
          li $v0,10
          syscall
      """
          .trimIndent()

  private fun session() =
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()

  private fun check(): ConsolePublicationCheck {
    fun state(value: Long, label: String? = null) =
        ConsolePublicationCheck.State(
            label = label,
            expectedRegisters = mapOf("s0" to value, "ra" to 0x1122334455667788),
            accessPolicies =
                listOf(
                    FunctionAccessPolicyCheck(
                        FunctionDataAddress("protected"),
                        FunctionDataAddress("end", 4),
                        checkReads = false,
                    ),
                ),
        )
    return ConsolePublicationCheck(
        "entering",
        emptyList(),
        listOf(state(0x1234567800000007), state(0x1234567800000008, "returned")),
        state(0x1234567800000009)
            .copy(expectedRegisters = mapOf("v0" to 10, "s0" to 0x1234567800000009)),
    )
  }

  private fun evaluate(
      text: String = source,
      publication: ConsolePublicationCheck = check(),
      clear: () -> Unit = {},
  ) =
      session().use { machine ->
        val path = directory.resolve("boundaries.asm")
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to text)),
            null,
            extended = true,
            warnings = false,
        )
        PreparedConsoleChecks.evaluate(
                machine.captureAssemblyImage {},
                listOf(ConsoleCheckCase("ordered", "", "", 2000, publication = publication)),
                {
                  clear()
                  session()
                },
            )
            .single()
      }

  @Test
  fun `distinct named boundaries check full register values without changing the return link`() {
    assertTrue(evaluate().passed)
    assertTrue(
        evaluate(
                publication =
                    check()
                        .copy(
                            label = "unused",
                            snapshots =
                                check().snapshots.map { it.copy(label = it.label ?: "entering") },
                        ),
            )
            .passed,
    )
    val wrong =
        check()
            .copy(
                snapshots = check().snapshots.map { it.copy(expectedRegisters = mapOf("s0" to 7)) },
            )
    assertFalse(evaluate(publication = wrong).passed)
    assertFalse(
        evaluate(
                publication =
                    check()
                        .copy(
                            finalState =
                                ConsolePublicationCheck.State(expectedRegisters = mapOf("s0" to 0)),
                        ),
            )
            .passed,
    )
    assertFalse(
        evaluate(publication = check().copy(snapshots = check().snapshots.reversed())).passed,
    )
    assertFalse(
        evaluate(
                publication =
                    check().copy(snapshots = listOf(check().snapshots[0].copy(label = "missing"))),
            )
            .passed,
    )
  }

  @Test
  fun `policies cover the prior marker instruction and distinct marker visit counts remain exact`() {
    val bad =
        evaluate(source.replace("entering:\n    nop", "entering:\n    sw $" + "zero,protected"))
    assertFalse(bad.passed)
    assertTrue(bad.failure!!.startsWith("Publication 2 at returned:"), bad.failure)
    val extra =
        source.replace(
            "done:\n",
            $$"done:\n bnezc $t0,exit\n nop\n li $t0,1\n bc returned\n nop\nexit:\n",
        )
    val result = evaluate(extra, check().copy(finalState = ConsolePublicationCheck.State()))
    assertFalse(result.passed)
    assertTrue(
        result.failure!!.contains("exactly 1 completed visits to public marker returned"),
        result.failure,
    )
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `register maps detach before acquiring a session and constructors reject ambiguous results`() {
    val registers = mutableMapOf("s0" to 9L)
    val publication =
        check().copy(snapshots = listOf(check().snapshots[0].copy(expectedRegisters = registers)))
    assertFalse(evaluate(publication = publication) { registers.clear() }.passed)
    assertThrows<IllegalArgumentException> { ConsolePublicationCheck.State(label = "bad label") }
    assertThrows<IllegalArgumentException> {
      check().copy(finalState = check().finalState.copy(label = "returned"))
    }
    for (map in
        listOf(
            mapOf("missing" to 0L),
            mapOf("v0" to 1L, $$"$v0" to 1L),
            (0..32).associate { it.toString() to 0L },
        )) {
      assertThrows<IllegalArgumentException> {
        ConsolePublicationCheck.State(expectedRegisters = map)
      }
    }
    val final = mutableMapOf("s0" to 0L)
    assertFalse(
        evaluate(
                publication =
                    check()
                        .copy(
                            finalState = ConsolePublicationCheck.State(expectedRegisters = final),
                        ),
            ) {
              final.clear()
            }
            .passed,
    )
  }
}
