package edu.missouristate.mars.plugin.learning

import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.plugin.learning.MarsDownloadedDebugCheckpoint.Outcome
import edu.missouristate.mars.plugin.learning.MarsDownloadedDebugCheckpoint.Pause
import edu.missouristate.mars.plugin.learning.MarsDownloadedDebugCheckpoint.Trace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MarsDownloadedDebugTraceTest {
  private fun stop(id: String, value: Long) =
      MarsNativeDebugStop(
          id,
          "Explain $id.",
          MarsLessonAnchor("interpret", "andi", 0),
          mapOf("\$t0" to value),
          "Observe $id.",
          emptyList(),
      )

  private val first = Pause(1, 0, 10, 0x100400000, 0x100400000, mapOf("\$t0" to 150L), true)

  @Test
  fun `both vector halves must match and unavailable or extra vectors cannot grant credit`() {
    val bits = VectorRegister(0x1234567800000001, Long.MIN_VALUE)
    val stop = stop("lanes", 150).copy(vectorRegisters = mapOf("\$w4" to bits))
    val trace = Trace(listOf(stop))
    assertEquals(Outcome.VALUES, trace.observe(first))
    assertEquals(
        Outcome.VALUES,
        trace.observe(first.copy(number = 2, vectorRegisters = mapOf("\$w4" to null))),
    )
    assertEquals(
        Outcome.VALUES,
        trace.observe(
            first.copy(number = 3, vectorRegisters = mapOf("\$w4" to bits.copy(low = 1))),
        ),
    )
    assertEquals(
        Outcome.VALUES,
        trace.observe(
            first.copy(number = 4, vectorRegisters = mapOf("\$w4" to bits.copy(high = 0))),
        ),
    )
    assertEquals(
        Outcome.VALUES,
        trace.observe(
            first.copy(number = 5, vectorRegisters = mapOf("\$w4" to bits, "\$w5" to bits)),
        ),
    )
    assertEquals(
        Outcome.HISTORY,
        trace.observe(
            first.copy(number = 6, revision = 1, vectorRegisters = mapOf("\$w4" to bits)),
        ),
    )
    assertEquals(0, trace.phase)
    assertEquals(
        Outcome.PASSED,
        trace.observe(first.copy(number = 7, vectorRegisters = mapOf("\$w4" to bits))),
    )
    val onlyVector = Trace(listOf(stop.copy(registers = emptyMap())))
    assertEquals(
        Outcome.PASSED,
        onlyVector.observe(
            first.copy(registers = emptyMap(), vectorRegisters = mapOf("\$w4" to bits)),
        ),
    )
  }

  @Test
  fun `ordered observations require new forward execution and full-width values`() {
    val trace = Trace(listOf(stop("masked", 150), stop("extended", -106)))
    assertNull(trace.observe(first.copy(number = 0)))
    assertEquals(Outcome.ADVANCED, trace.observe(first))
    assertEquals(1, trace.phase)
    assertNull(trace.observe(first.copy(registers = mapOf("\$t0" to -106))))
    assertEquals(
        Outcome.BOUNDARY,
        trace.observe(first.copy(number = 2, registers = mapOf("\$t0" to -106))),
    )
    assertEquals(
        Outcome.VALUES,
        trace.observe(
            first.copy(number = 3, instructions = 11, registers = mapOf("\$t0" to 0xffffff96)),
        ),
    )
    assertEquals(
        Outcome.PASSED,
        trace.observe(first.copy(number = 4, instructions = 11, registers = mapOf("\$t0" to -106))),
    )
    assertEquals(2, trace.phase)
    assertNull(trace.observe(first.copy(number = 5, revision = 1)))
  }

  @Test
  fun `history missing anchors wrong boundaries and wrong inputs cannot grant a stop`() {
    val trace = Trace(listOf(stop("masked", 150)))
    assertEquals(Outcome.HISTORY, trace.observe(first.copy(revision = 1)))
    assertEquals(Outcome.ANCHOR, trace.observe(first.copy(number = 2, address = null)))
    assertEquals(Outcome.BOUNDARY, trace.observe(first.copy(number = 3, pc = first.pc + 4)))
    assertEquals(Outcome.INPUT, trace.observe(first.copy(number = 4, inputsMatch = false)))
    assertEquals(Outcome.VALUES, trace.observe(first.copy(number = 5, registers = emptyMap())))
    assertEquals(0, trace.phase)
    assertEquals(Outcome.PASSED, trace.observe(first.copy(number = 6)))
  }
}
