package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.devices.MarsBotDevice.Companion.instructionRange
import edu.missouristate.mars.devices.MarsBotWorld.Companion.BOARD_SIZE

/** Data-only expectations for integral board positions and a complete continuous trail. */
data class ConsoleMarsBotCheck(
    val instructionsPerTick: Int,
    val x: Int,
    val y: Int,
    val heading: Int,
    val moving: Boolean,
    val penDown: Boolean,
    val trail: List<Pair<Int, Int>>,
) {
  init {
    require(instructionsPerTick in instructionRange) {
      "MarsBot movement rate is outside the device range"
    }
    require(x in 0 until BOARD_SIZE && y in 0 until BOARD_SIZE && heading in 0..359) {
      "MarsBot state needs board coordinates and a heading from 0 through 359"
    }
    require(
        trail.size != 1 &&
            trail.size <= MAX_VERTICES &&
            trail.all { (px, py) -> px in 0 until BOARD_SIZE && py in 0 until BOARD_SIZE },
    ) {
      "Choose an empty trail or two through 256 integral board vertices"
    }
  }

  internal fun verify(bot: MarsBotCheck) {
    bot.expectState(x.toDouble(), y.toDouble(), heading, moving, penDown)
    bot.expectTrail(*trail.map { (px, py) -> px.toDouble() to py.toDouble() }.toTypedArray())
  }

  companion object {
    const val MAX_VERTICES = 256
  }
}
