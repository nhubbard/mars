package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.BranchOutcome.NOT_TAKEN
import edu.missouristate.mars.api.StepEvent.BranchOutcome.TAKEN
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.tools.BranchHistoryTable.Observation
import edu.missouristate.mars.plugin.tools.BranchHistoryTable.Options
import edu.missouristate.mars.plugin.tools.BranchHistoryTable.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BranchHistoryTableTest {
  private val decoders =
      MarsTarget.entries.associateWith {
        ExecutedInstructionCache(InstructionDefaults.forTarget(it))
      }

  private fun BranchHistoryTable.record(event: StepEvent) {
    record(event, decoders.getValue(event.target).decode(event.instruction).branchTargetOffset)
  }

  @Test
  fun `two outcomes change a two-bit prediction and branches sharing a row train it together`() {
    val table = BranchHistoryTable()
    table.configure(Options(8, 2, false))
    val pc = 0x00400004
    val alias = pc + 32
    fun record(address: Int, taken: Boolean) {
      table.record(StepEvent(address, 0x1000fffe, 0, null, if (taken) TAKEN else NOT_TAKEN))
    }
    record(pc, true)
    assertEquals(Row(listOf(false, true), false, 0, 1), table.snapshot().rows[1])
    record(alias, true)
    assertEquals(Row(listOf(true, true), true, 0, 2), table.snapshot().rows[1])
    record(pc, true)
    record(alias, false)
    assertEquals(Row(listOf(true, false), true, 1, 3), table.snapshot().rows[1])
    record(pc, false)
    val result = table.snapshot()
    assertEquals(Row(listOf(false, false), false, 1, 4), result.rows[1])
    assertEquals(20.0, result.rows[1].precision)
    assertEquals(0.0, result.rows[0].precision)
    assertEquals(
        Observation(
            pc.toLong(),
            0x1000fffe,
            pc - 4L,
            1,
            predictedTaken = true,
            taken = false,
            MarsTarget.MARS32,
        ),
        result.recent.last(),
    )
  }

  @Test
  fun `compact branches keep their full program counters and signed 21-bit destinations`() {
    val table = BranchHistoryTable()
    val pc = 0x100400004L
    val sourceRegister = 8
    val opcode = 0b110110 shl 26
    for ((displacement, outcome) in listOf(0x10000 to TAKEN, -0x10000 to NOT_TAKEN)) {
      val word = opcode or (sourceRegister shl 21) or (displacement and 0x1fffff)
      table.record(
          StepEvent(
              pc.toInt(),
              word,
              1,
              branchOutcome = outcome,
              pcBits = pc,
              target = MarsTarget.MARS64,
          ),
      )
      val observation = table.snapshot().recent.last()
      assertEquals(pc, observation.pc)
      assertEquals(pc + 4 + displacement * 4, observation.target)
      assertEquals(1, observation.index)
      assertEquals(MarsTarget.MARS64, observation.architecture)
    }
    val nal = 0x04100000
    table.record(
        StepEvent(
            pc.toInt(),
            nal,
            1,
            branchOutcome = NOT_TAKEN,
            pcBits = pc,
            target = MarsTarget.MARS64,
        ),
    )
    assertEquals(null, table.snapshot().recent.last().target)
  }

  @Test
  fun `configuration resets history and the execution log stays bounded`() {
    assertThrows<IllegalArgumentException> { Options(size = 7) }
    assertThrows<IllegalArgumentException> { Options(historyBits = 3) }
    val table = BranchHistoryTable()
    val untouched = BranchHistoryTable()
    val initial = table.snapshot()
    table.record(StepEvent(0, 0, 1))
    assertEquals(initial, table.snapshot())
    table.setConnected(false)
    val step = StepEvent(0x80000180.toInt(), 0x10000000, 2, null, TAKEN)
    table.record(step)
    assertEquals(initial.copy(connected = false), table.snapshot())

    table.configure(Options(32, 1, true))
    assertEquals(false, table.snapshot().connected)
    table.setConnected(true)
    repeat(130) { table.record(step) }
    val trained = table.snapshot()
    assertEquals(Row(listOf(true), true, 130, 0), trained.rows[0])
    assertEquals(128, trained.recent.size)
    assertEquals(initial, untouched.snapshot())
    table.reset()
    assertEquals(emptyList<Observation>(), table.snapshot().recent)
    assertEquals(Row(listOf(true), true, 0, 0), table.snapshot().rows[0])
    table.record(StepEvent(0x80000180.toInt(), 0x14000000, 3, null, NOT_TAKEN))
    assertEquals(Row(listOf(false), false, 0, 1), table.snapshot().rows[0])
  }
}
