package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.MarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedConsoleMarsBotChecksTest {
  @TempDir lateinit var directory: Path

  private fun session(): MarsContext =
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()

  private val east =
      $$"""
      .data
      side: .word 16
      guard: .word 0x13579bdf
      .text
      main:
          dli $s0,0x00000000ffff8000
          nop
          lw $s1,side
          li $t0,1
          sw $t0,0x20($s0)
          li $t1,90
          sw $t1,0x10($s0)
          sw $t0,0x50($s0)
      wait:
          lw $t2,0x30($s0)
          bnec $t2,$s1,wait
          nop
          sw $zero,0x50($s0)
          sw $zero,0x20($s0)
          li $v0,10
          syscall
      """
          .trimIndent()

  private fun image(source: String = east): AssemblyImage =
      session().use { machine ->
        val path = directory.resolve("east.asm")
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun expectation(side: Int = 16, rate: Int = 64) =
      ConsoleMarsBotCheck(
          rate,
          side,
          0,
          90,
          moving = false,
          penDown = false,
          trail = listOf(0 to 0, side to 0),
      )

  @Test
  fun `fresh device cases run actual feedback loops with different implementations sides and rates`() {
    for (source in listOf(east, east.replace($$"bnec $t2,$s1,wait", $$"bltuc $t2,$s1,wait"))) {
      val cases =
          listOf(1, 16, 63).flatMap { side ->
            listOf(8, 64, 128).map { rate ->
              val bytes = side.toString(16).padStart(2, '0') + "000000df9b5713"
              ConsoleCheckCase(
                  "side $side rate $rate",
                  "",
                  "",
                  100_000,
                  memory = listOf(ConsoleMemoryCheck(0x10010000, bytes)),
                  initialMemory = listOf(ConsoleMemoryCheck(0x10010000, bytes)),
                  stores =
                      listOf(
                          ConsoleStoreCheck(0x10010000, 8),
                          ConsoleStoreCheck(0xffff8030, 4),
                          ConsoleStoreCheck(0xffff8040, 4),
                      ),
                  marsBot = expectation(side, rate),
              )
            }
          }
      val machines = mutableListOf<MarsContext>()
      val results =
          PreparedConsoleChecks.evaluate(image(source), cases, { session().also(machines::add) })
      assertTrue(results.all { it.passed }, results.toString())
      assertEquals(9, machines.toSet().size)
      for (machine in machines) assertThrows<IllegalStateException> {
        machine.claimStoppedSession()
      }
    }
  }

  @Test
  fun `same endpoint does not excuse missing ink a moving robot or an incorrect complete trail`() {
    val expected = ConsoleCheckCase("east", "", "", 100_000, marsBot = expectation())
    for (fault in
        listOf(
            east.replace($$"sw $t0,0x20($s0)", "nop"),
            east.replace($$"sw $zero,0x50($s0)", "nop"),
        )) {
      val result =
          PreparedConsoleChecks.evaluate(image(fault), listOf(expected), ::session).single()
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains("MarsBot"))
    }
    val wrongTrail =
        expected.copy(marsBot = expectation().copy(trail = listOf(0 to 0, 8 to 0, 16 to 0)))
    assertFalse(
        PreparedConsoleChecks.evaluate(image(), listOf(wrongTrail), ::session).single().passed,
    )
    val wrongEndpoint = expected.copy(marsBot = expectation().copy(trail = listOf(0 to 1, 16 to 0)))
    assertFalse(
        PreparedConsoleChecks.evaluate(image(), listOf(wrongEndpoint), ::session).single().passed,
    )
    val empty =
        ConsoleCheckCase(
            "no motion",
            "",
            "",
            marsBot = expectation().copy(x = 0, heading = 0, trail = emptyList()),
        )
    assertTrue(
        PreparedConsoleChecks.evaluate(
                image($$".text\nli $v0,10\nsyscall\n"),
                listOf(empty),
                ::session,
            )
            .single()
            .passed,
    )
  }

  @Test
  fun `coordinate and input writes restored to their original bytes are still rejected`() {
    for ((injection, region, size) in
        listOf(
            Triple($$"sw $zero,0x30($s0)", 0xffff8030L, 4),
            Triple($$"li $t1,16\nsw $t1,side", 0x10010000L, 8),
        )) {
      val program = image(east.replaceFirst("nop", injection))
      val untraced =
          ConsoleCheckCase("final state correct", "", "", 100_000, marsBot = expectation())
      assertTrue(
          PreparedConsoleChecks.evaluate(program, listOf(untraced), ::session).single().passed,
      )
      val traced = untraced.copy(stores = listOf(ConsoleStoreCheck(region, size)))
      assertFalse(
          PreparedConsoleChecks.evaluate(program, listOf(traced), ::session).single().passed,
      )
    }
  }

  @Test
  fun `device history is detached before execution and subscriptions close after success or failure`() {
    val vertices = mutableListOf(0 to 0, 16 to 0)
    val case =
        ConsoleCheckCase(
            "detached",
            "",
            "",
            100_000,
            marsBot = expectation().copy(trail = vertices),
        )
    lateinit var machine: MarsContext
    val result =
        PreparedConsoleChecks.evaluate(
                image(),
                listOf(case),
                {
                  vertices.clear()
                  session().also { machine = it }
                },
            )
            .single()
    assertTrue(result.passed)
    assertEquals(0, machine.memory.countObservers())
    val failure =
        PreparedConsoleChecks.evaluate(
                image(".text\nbreak\n"),
                listOf(case),
                { session().also { machine = it } },
            )
            .single()
    assertFalse(failure.passed)
    assertEquals(0, machine.memory.countObservers())
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `device data rejects invalid domains before connecting or executing`() {
    val valid = expectation()
    for (invalid in
        listOf(
            { valid.copy(instructionsPerTick = 0) },
            { valid.copy(instructionsPerTick = 1_000_001) },
            { valid.copy(x = -1) },
            { valid.copy(x = 512) },
            { valid.copy(y = -1) },
            { valid.copy(y = 512) },
            { valid.copy(heading = -1) },
            { valid.copy(heading = 360) },
            { valid.copy(trail = listOf(0 to 0)) },
            { valid.copy(trail = List(257) { 0 to 0 }) },
            { valid.copy(trail = listOf(-1 to 0, 0 to 0)) },
            { valid.copy(trail = listOf(0 to -1, 0 to 0)) },
            { valid.copy(trail = listOf(512 to 0, 0 to 0)) },
            { valid.copy(trail = listOf(0 to 512, 0 to 0)) },
        )) assertThrows<IllegalArgumentException> { invalid() }
    valid.copy(
        instructionsPerTick = 1,
        x = 511,
        y = 511,
        heading = 359,
        trail = List(256) { 511 to 511 },
    )
  }
}
