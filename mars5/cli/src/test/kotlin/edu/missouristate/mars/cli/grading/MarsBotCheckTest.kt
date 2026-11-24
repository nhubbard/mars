package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.mips.hardware.AccessOrigin.Companion.duringExecution
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsBotCheckTest {
  @Test
  fun `case owns one device connected before execution and cleans up on failure`(
      @TempDir directory: Path,
  ) {
    val entry = directory.resolve("case.asm").also { it.writeText("nop\nnop\n") }
    DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { session ->
      val program = session.assemble(listOf(entry))
      assertThrows(CaseFailure::class.java) {
        AssemblyCase(session, program.symbolMap(), ByteArrayOutputStream()).use { case ->
          assertThrows(IllegalArgumentException::class.java) { case.connectMarsBot(0) }
          case.connectMarsBot()
          assertThrows(CaseFailure::class.java) { case.connectMarsBot() }
          case.step()
          assertThrows(CaseFailure::class.java) { case.connectMarsBot() }
          throw CaseFailure("exercise cleanup")
        }
      }
      assertEquals(0, session.memory.countObservers())
    }
  }

  @Test
  fun `clock listener and memory subscription close together while assertions retain the stopped state`(
      @TempDir directory: Path,
  ) {
    val entry = directory.resolve("clock.asm").also { it.writeText("nop\nnop\nnop\nnop\n") }
    DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { session ->
      session.assemble(listOf(entry))
      val bot = MarsBotCheck(session, 1)
      bot.expectState(0.0, 0.0, 0, false, false)
      bot.expectTrail()
      duringExecution<Unit, Exception> {
        session.memory.setWord(0xffff8010.toInt(), 90)
        session.memory.setWord(0xffff8020.toInt(), 1)
        session.memory.setWord(0xffff8050.toInt(), 1)
      }
      session.simulator().runAsync(2, null).join()
      bot.expectState(2.0, 0.0, 90, true, true)
      bot.expectTrail(0.0 to 0.0, 2.0 to 0.0)
      bot.expectState(2.01, 0.0, 90, true, true, tolerance = 0.02)
      assertThrows(CaseFailure::class.java) { bot.expectState(2.0, 0.0, 90, false, true) }
      assertThrows(CaseFailure::class.java) { bot.expectState(2.0, 0.0, 0, true, true) }
      assertThrows(CaseFailure::class.java) { bot.expectState(2.0, 0.0, 90, true, false) }
      assertThrows(CaseFailure::class.java) { bot.expectState(1.0, 0.0, 90, true, true) }
      assertThrows(CaseFailure::class.java) { bot.expectTrail() }
      assertThrows(CaseFailure::class.java) { bot.expectTrail(0.0 to 0.0, 1.0 to 0.0) }
      assertThrows(CaseFailure::class.java) { bot.expectTrail(0.0 to 1.0, 2.0 to 0.0) }
      assertThrows(IllegalArgumentException::class.java) { bot.expectTrail(0.0 to 0.0) }
      assertThrows(IllegalArgumentException::class.java) {
        bot.expectTrail(Double.NaN to 0.0, 2.0 to 0.0)
      }
      assertThrows(IllegalArgumentException::class.java) { bot.expectTrail(tolerance = -1.0) }
      assertThrows(IllegalArgumentException::class.java) {
        bot.expectTrail(tolerance = Double.POSITIVE_INFINITY)
      }
      assertThrows(IllegalArgumentException::class.java) {
        bot.expectState(Double.NaN, 0.0, 0, false, false)
      }
      bot.close()
      bot.close()
      assertEquals(0, session.memory.countObservers())
      session.simulator().runAsync(1, null).join()
      bot.expectState(2.0, 0.0, 90, true, true)
      bot.expectTrail(0.0 to 0.0, 2.0 to 0.0)
    }
  }
}
