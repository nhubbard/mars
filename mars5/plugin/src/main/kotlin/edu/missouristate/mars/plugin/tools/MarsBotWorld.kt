package edu.missouristate.mars.plugin.tools

import kotlin.math.abs

/** The 512-pixel MarsBot board and its immutable pen history. */
internal data class MarsBotWorld(
    val position: Point = Point(0.0, 0.0),
    val heading: Int = 0,
    val moving: Boolean = false,
    val penDown: Boolean = false,
    val trail: Trail? = null,
) {
    data class Point(val x: Double, val y: Double)
    data class Stroke(val from: Point, val to: Point)

    class Trail(val stroke: Stroke, val previous: Trail?) {
        fun strokes(): Sequence<Stroke> = generateSequence(this) { it.previous }.map { it.stroke }
    }

    fun moveOnePixel(): MarsBotWorld {
        val direction = when (heading) {
            0 -> Point(0.0, -1.0)
            90 -> Point(1.0, 0.0)
            180 -> Point(0.0, 1.0)
            270 -> Point(-1.0, 0.0)
            else -> Math.toRadians(heading.toDouble()).let { Point(StrictMath.sin(it), -StrictMath.cos(it)) }
        }
        val next = Point((position.x + direction.x).coerceIn(0.0, lastPixel),
            (position.y + direction.y).coerceIn(0.0, lastPixel))
        if (next == position) return this
        val path = if (penDown) extendTrail(next) else trail
        return copy(position = next, trail = path)
    }

    private fun extendTrail(next: Point): Trail {
        val last = trail
        if (last != null && last.stroke.to == position) {
            val dx = position.x - last.stroke.from.x
            val dy = position.y - last.stroke.from.y
            val nextDx = next.x - position.x
            val nextDy = next.y - position.y
            // Merge straight, forward motion while preserving corners and pen-up gaps.
            if (abs(dx * nextDy - dy * nextDx) < 1e-9 && dx * nextDx + dy * nextDy > 0) {
                return Trail(Stroke(last.stroke.from, next), last.previous)
            }
        }
        return Trail(Stroke(position, next), last)
    }

    companion object {
        const val boardSize = 512
        private const val lastPixel = 511.0
    }
}
