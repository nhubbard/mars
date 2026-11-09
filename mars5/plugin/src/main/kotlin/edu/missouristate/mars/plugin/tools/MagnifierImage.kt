package edu.missouristate.mars.plugin.tools

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import kotlin.math.roundToInt

internal enum class MagnifierZoom(val factor: Double) {
    ONE(1.0), ONE_AND_HALF(1.5), TWO(2.0), TWO_AND_HALF(2.5), THREE(3.0), THREE_AND_HALF(3.5), FOUR(4.0),
}

internal enum class MagnifierAlignment { CENTER, UPPER_LEFT }

internal data class MagnifierStroke(
    val start: Point2D.Double,
    val points: List<Point2D.Double>,
    val color: Color,
    val width: Float,
)

internal data class MagnifierLayout(
    val imageSize: Dimension,
    val imageOrigin: Point,
    val scrollPosition: Point,
    val scale: Double,
) {
    /** The point is relative to the scroll pane's content component. */
    fun imagePoint(point: Point): Point2D.Double =
        Point2D.Double((point.x - imageOrigin.x) / scale, (point.y - imageOrigin.y) / scale)
}

/** A static desktop snapshot with logical dimensions and independent per-display resolution. */
internal class MagnifierImage(private val area: Rectangle, private val tiles: List<MagnifierTile>) {
    fun layout(viewport: Dimension, zoom: MagnifierZoom, alignment: MagnifierAlignment): MagnifierLayout {
        val scale = zoom.factor
        val size = Dimension((area.width * scale).roundToInt(), (area.height * scale).roundToInt())
        val origin = Point()
        val scroll = Point()
        if (alignment == MagnifierAlignment.CENTER) {
            origin.setLocation(maxOf(0, viewport.width - size.width) / 2, maxOf(0, viewport.height - size.height) / 2)
            scroll.setLocation(maxOf(0, size.width - viewport.width) / 2, maxOf(0, size.height - viewport.height) / 2)
        }
        return MagnifierLayout(size, origin, scroll, scale)
    }

    fun paint(graphics: Graphics2D, layout: MagnifierLayout, strokes: List<MagnifierStroke>) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(layout.imageOrigin.x, layout.imageOrigin.y)
            g.clipRect(0, 0, layout.imageSize.width, layout.imageSize.height)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            for ((bounds, image) in tiles) {
                // Round boundaries independently so adjoining monitors still meet at half-step zooms.
                val left = ((bounds.x - area.x) * layout.scale).roundToInt()
                val top = ((bounds.y - area.y) * layout.scale).roundToInt()
                val right = ((bounds.x + bounds.width - area.x) * layout.scale).roundToInt()
                val bottom = ((bounds.y + bounds.height - area.y) * layout.scale).roundToInt()
                g.drawImage(image, left, top, right - left, bottom - top, null)
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (stroke in strokes) {
                g.color = stroke.color
                g.stroke = BasicStroke(stroke.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val path = Path2D.Double()
                path.moveTo(stroke.start.x * layout.scale, stroke.start.y * layout.scale)
                for (point in stroke.points) path.lineTo(point.x * layout.scale, point.y * layout.scale)
                g.draw(path)
            }
        } finally {
            g.dispose()
        }
    }
}
