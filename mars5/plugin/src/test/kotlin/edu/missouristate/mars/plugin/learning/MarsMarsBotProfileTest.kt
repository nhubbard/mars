package edu.missouristate.mars.plugin.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarsMarsBotProfileTest {
  private fun profile(name: String) =
      MarsConsoleCaseData.parse(
          checkNotNull(javaClass.getResourceAsStream("/learning/marsbot-$name.cases.json")).use {
            it.readBytes()
          },
      )

  @Test
  fun `original square cases preserve changed input guards ordered sides and forbidden writes`() {
    val cases = profile("square")
    assertEquals(
        listOf("side 16 clock 64", "side 1 clock 64", "side 63 clock 17"),
        cases.map { it.name },
    )
    for ((case, pair) in cases.zip(listOf(16 to 64, 1 to 64, 63 to 17))) {
      val (side, rate) = pair
      val bot = checkNotNull(case.marsBot)
      assertEquals(rate, bot.instructionsPerTick)
      assertEquals(listOf(0 to 0, side to 0, side to side, 0 to side, 0 to 0), bot.trail)
      assertEquals(listOf(0, 0, 0), listOf(bot.x, bot.y, bot.heading))
      assertTrue(!bot.moving && !bot.penDown)
      assertEquals(case.initialMemory, case.memory)
      assertEquals(
          listOf(0x10010000L to 8, 0xffff8030L to 4, 0xffff8040L to 4),
          case.stores.map { it.address to it.byteLength },
      )
      assertTrue(case.stores.all { it.port == null })
      assertEquals(100_000L, case.instructionLimit)
      assertEquals("", case.expectedOutput)
      assertEquals("", case.input)
    }
  }

  @Test
  fun `one leg uses all published length rate pairs and final state rather than an invented square`() {
    val cases = profile("east")
    assertEquals(16, cases.size)
    assertEquals(
        setOf(1, 2, 16, 63)
            .flatMap { side -> listOf(8, 17, 64, 128).map { rate -> side to rate } }
            .toSet(),
        cases
            .map { checkNotNull(it.marsBot).let { bot -> bot.x to bot.instructionsPerTick } }
            .toSet(),
    )
    for (case in cases) {
      val bot = checkNotNull(case.marsBot)
      assertEquals(listOf(0 to 0, bot.x to 0), bot.trail)
      assertEquals(0, bot.y)
      assertEquals(90, bot.heading)
      assertTrue(!bot.moving && !bot.penDown)
      assertEquals(case.initialMemory, case.memory)
    }
  }
}
