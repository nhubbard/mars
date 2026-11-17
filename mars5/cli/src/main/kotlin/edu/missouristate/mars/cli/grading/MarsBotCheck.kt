package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.devices.MarsBotDevice
import edu.missouristate.mars.devices.MarsBotWorld.Point
import kotlin.math.abs

/** Headless assertions over the IDE's actual device model, advanced at the same onStep boundary. */
class MarsBotCheck internal constructor(machine: MarsContext, instructionsPerTick: Int) : AutoCloseable {
    private val bot = MarsBotDevice(true).apply { setInstructionsPerTick(instructionsPerTick) }
    private val simulator = machine.simulator()
    private val observation = bot.observe(machine)
    private var closed = false
    private val clock = object : SimulatorListener {
        override fun onStep(event: StepEvent) = bot.instructionCompleted()
        override fun onError(event: ErrorEvent) = Unit
        override fun onStop(event: StopEvent) = Unit
    }

    init {
        simulator.addListener(clock)
    }

    fun expectState(x: Double, y: Double, heading: Int, moving: Boolean, penDown: Boolean, tolerance: Double = 1e-9) {
        requireTolerance(tolerance)
        require(x.isFinite() && y.isFinite()) { "Expected coordinates must be finite" }
        val world = bot.snapshot().world
        requireCase(matches(world.position, x to y, tolerance) && world.heading == heading &&
            world.moving == moving && world.penDown == penDown) {
            "Expected MarsBot at ($x, $y), heading $heading, moving=$moving, penDown=$penDown; " +
                "got ${world.position}, heading ${world.heading}, moving=${world.moving}, penDown=${world.penDown}"
        }
    }

    /** Check the complete continuous trail in chronological order, including corners and no extra strokes. */
    fun expectTrail(vararg vertices: Pair<Double, Double>, tolerance: Double = 1e-9) {
        requireTolerance(tolerance)
        require(vertices.size != 1 && vertices.all { it.first.isFinite() && it.second.isFinite() }) {
            "Choose no vertices for an empty trail, or at least two finite vertices"
        }
        val strokes = bot.snapshot().world.trail?.strokes()?.toList()?.asReversed().orEmpty()
        val expectedCount = (vertices.size - 1).coerceAtLeast(0)
        requireCase(strokes.size == expectedCount) { "Expected $expectedCount MarsBot strokes, got ${strokes.size}" }
        for ((index, stroke) in strokes.withIndex()) {
            requireCase(matches(stroke.from, vertices[index], tolerance) && matches(stroke.to, vertices[index + 1], tolerance)) {
                "Expected MarsBot stroke ${index + 1}: ${vertices[index]} to ${vertices[index + 1]}, got $stroke"
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        simulator.removeListener(clock)
        observation.close()
    }

    private fun requireTolerance(tolerance: Double) {
        require(tolerance.isFinite() && tolerance >= 0) { "Tolerance must be finite and nonnegative" }
    }

    private fun matches(point: Point, expected: Pair<Double, Double>, tolerance: Double) =
        abs(point.x - expected.first) <= tolerance && abs(point.y - expected.second) <= tolerance
}
