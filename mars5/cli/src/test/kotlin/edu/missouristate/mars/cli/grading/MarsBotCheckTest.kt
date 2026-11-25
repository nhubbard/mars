package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.mips.hardware.AccessOrigin.Companion.duringExecution
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class MarsBotCheckTest {
  @Test
  fun `case owns one device connected before execution and cleans up on failure`(
      @TempDir directory: Path,
  ) {
    val entry = directory.resolve("case.asm").also { it.writeText("nop\nnop\n") }
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { session ->
          val program = session.assemble(listOf(entry))
          assertThrows<CaseFailure> {
            AssemblyCase(session, program.symbolMap(), ByteArrayOutputStream()).use { case ->
              assertThrows<IllegalArgumentException> { case.connectMarsBot(0) }
              case.connectMarsBot()
              assertThrows<CaseFailure> { case.connectMarsBot() }
              case.step()
              assertThrows<CaseFailure> { case.connectMarsBot() }
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
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { session ->
          session.assemble(listOf(entry))
          val bot = MarsBotCheck(session, 1)
          bot.expectState(0.0, 0.0, 0, moving = false, penDown = false)
          bot.expectTrail()
          duringExecution<Unit, Exception> {
            session.memory.setWord(0xffff8010.toInt(), 90)
            session.memory.setWord(0xffff8020.toInt(), 1)
            session.memory.setWord(0xffff8050.toInt(), 1)
          }
          session.simulator().runAsync(2, null).join()
          bot.expectState(2.0, 0.0, 90, moving = true, penDown = true)
          bot.expectTrail(0.0 to 0.0, 2.0 to 0.0)
          bot.expectState(2.01, 0.0, 90, moving = true, penDown = true, tolerance = 0.02)
          assertThrows<CaseFailure> {
            bot.expectState(2.0, 0.0, 90, moving = false, penDown = true)
          }
          assertThrows<CaseFailure> {
            bot.expectState(2.0, 0.0, 0, moving = true, penDown = true)
          }
          assertThrows<CaseFailure> {
            bot.expectState(2.0, 0.0, 90, moving = true, penDown = false)
          }
          assertThrows<CaseFailure> {
            bot.expectState(1.0, 0.0, 90, moving = true, penDown = true)
          }
          assertThrows<CaseFailure> { bot.expectTrail() }
          assertThrows<CaseFailure> { bot.expectTrail(0.0 to 0.0, 1.0 to 0.0) }
          assertThrows<CaseFailure> { bot.expectTrail(0.0 to 1.0, 2.0 to 0.0) }
          assertThrows<IllegalArgumentException> { bot.expectTrail(0.0 to 0.0) }
          assertThrows<IllegalArgumentException> {
            bot.expectTrail(Double.NaN to 0.0, 2.0 to 0.0)
          }
          assertThrows<IllegalArgumentException> { bot.expectTrail(tolerance = -1.0) }
          assertThrows<IllegalArgumentException> {
            bot.expectTrail(tolerance = Double.POSITIVE_INFINITY)
          }
          assertThrows<IllegalArgumentException> {
            bot.expectState(
                Double.NaN,
                0.0,
                0,
                moving = false,
                penDown = false,
            )
          }
          bot.close()
          bot.close()
          assertEquals(0, session.memory.countObservers())
          session.simulator().runAsync(1, null).join()
          bot.expectState(2.0, 0.0, 90, moving = true, penDown = true)
          bot.expectTrail(0.0 to 0.0, 2.0 to 0.0)
        }
  }
}
