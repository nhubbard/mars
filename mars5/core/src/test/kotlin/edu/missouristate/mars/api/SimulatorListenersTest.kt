package edu.missouristate.mars.api

import edu.missouristate.mars.TestSessions
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class SimulatorListenersTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `callbacks keep their native receiver arguments thread and configuration snapshot`() {
    val step = StepEvent(0x00400000, 0, 1)
    val stop = StopEvent(StopEvent.Reason.STEP_LIMIT, 0)
    val error = ErrorEvent(null, null)
    val thread = Thread.currentThread()
    val calls = mutableListOf<String>()
    val receivers = mutableListOf<SimulatorListener>()
    lateinit var captured: SimulatorListenerBuilder
    val listener = simulatorListener {
      captured = this
      onStep {
        assertSame(step, it)
        assertSame(thread, Thread.currentThread())
        receivers += this
        calls += "step"
      }
      onBackstep { calls += "backstep $it" }
      onStop {
        assertSame(stop, it)
        calls += "stop"
      }
      onError {
        assertSame(error, it)
        calls += "error"
      }
    }
    captured.onStep { calls += "changed after construction" }
    listener.onStep(step)
    listener.onBackstep(0)
    listener.onStop(stop)
    listener.onError(error)
    assertEquals(listOf("step", "backstep 0", "stop", "error"), calls)
    assertSame(listener, receivers.single())
    assertNotSame(listener, simulatorListener {})
  }

  @Test
  fun `omitted callbacks remain harmless and callback failures propagate unchanged`() {
    val step = StepEvent(0, 0, 0)
    val empty = simulatorListener {}
    empty.onStep(step)
    empty.onBackstep(0)
    empty.onStop(StopEvent(StopEvent.Reason.STOP_REQUEST, 0))
    empty.onError(ErrorEvent(null, null))
    val failure = IllegalStateException("listener failure")
    val throwing = simulatorListener { onStep { throw failure } }
    assertSame(failure, assertThrows<IllegalStateException> { throwing.onStep(step) })
  }

  @Test
  fun `removing the callback receiver unregisters exactly that listener during simulation`() {
    val source =
        Files.writeString(
            directory.resolve("listeners.asm"),
            $$".text\nli $t0,1\nli $v0,10\nsyscall\n",
        )
    TestSessions.create().use { session ->
      session.assemble(listOf(source))
      val simulator = session.simulator()
      var removedSteps = 0
      val steps = mutableListOf<StepEvent>()
      val stops = mutableListOf<StopEvent>()
      simulator.addListener(
          simulatorListener {
            onStep {
              removedSteps++
              simulator.removeListener(this)
            }
          },
      )
      simulator.addListener(
          simulatorListener {
            onStep { steps += it }
            onStop { stops += it }
          },
      )
      simulator.runAsync(8, null).get(5, SECONDS)
      assertEquals(1, removedSteps)
      assertEquals(listOf(1L, 2L, 3L), steps.map { it.stepCount })
      assertEquals(StopEvent.Reason.NORMAL_EXIT, stops.single().reason)
    }
  }
}
