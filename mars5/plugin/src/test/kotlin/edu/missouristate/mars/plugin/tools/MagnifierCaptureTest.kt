package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.plugin.tools.MagnifierAlignment.CENTER
import edu.missouristate.mars.plugin.tools.MagnifierAlignment.UPPER_LEFT
import edu.missouristate.mars.plugin.tools.MagnifierCapture.Result.Captured
import edu.missouristate.mars.plugin.tools.MagnifierCapture.Result.Failure
import edu.missouristate.mars.plugin.tools.MagnifierZoom.FOUR
import edu.missouristate.mars.plugin.tools.MagnifierZoom.ONE
import edu.missouristate.mars.plugin.tools.MagnifierZoom.ONE_AND_HALF
import edu.missouristate.mars.plugin.tools.MagnifierZoom.TWO
import java.awt.AWTException
import java.awt.Color
import java.awt.Dimension
import java.awt.HeadlessException
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.Point2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MagnifierCaptureTest {
    @Test
    fun `mixed resolution displays retain their logical bounds at fractional zoom`() {
        val area = Rectangle(-3, 0, 7, 3)
        val leftReads = mutableListOf<Rectangle>()
        val rightReads = mutableListOf<Rectangle>()
        val capture = MagnifierCapture {
            listOf(
                MagnifierDisplay(Rectangle(-20, -4, 20, 20)) {
                    leftReads += Rectangle(it)
                    it.translate(1, 1)
                    BaseMultiResolutionImage(bitmap(3, 3, Color.PINK), bitmap(6, 6, Color.BLUE))
                },
                MagnifierDisplay(Rectangle(0, 0, 10, 8)) {
                    rightReads += Rectangle(it)
                    val high = bitmap(8, 6, Color.GREEN)
                    for (x in 4 until 8) for (y in 0 until 6) high.setRGB(x, y, Color.YELLOW.rgb)
                    BaseMultiResolutionImage(bitmap(4, 3, Color.MAGENTA), high)
                },
                MagnifierDisplay(Rectangle(50, 50, 10, 10)) { error("Offscreen display should not be read") },
            )
        }
        val image = assertInstanceOf(Captured::class.java, capture.capture(area)).image
        area.translate(10, 10)
        assertEquals(listOf(Rectangle(-3, 0, 3, 3)), leftReads)
        assertEquals(listOf(Rectangle(0, 0, 4, 3)), rightReads)
        val layout = image.layout(Dimension(20, 15), ONE_AND_HALF, UPPER_LEFT)
        assertEquals(Dimension(11, 5), layout.imageSize)
        val rendered = render(image, layout)
        assertEquals(Color.BLUE.rgb, rendered.getRGB(0, 0))
        assertEquals(Color.BLUE.rgb, rendered.getRGB(4, 2))
        assertEquals(Color.GREEN.rgb, rendered.getRGB(5, 2))
        assertEquals(Color.YELLOW.rgb, rendered.getRGB(10, 4))
        assertEquals(Color.BLACK.rgb, rendered.getRGB(11, 4))
    }

    @Test
    fun `uncovered space between displays retains the viewport background`() {
        val image = assertInstanceOf(Captured::class.java, MagnifierCapture {
            listOf(
                MagnifierDisplay(Rectangle(-4, -4, 4, 4)) { BaseMultiResolutionImage(bitmap(2, 2, Color.WHITE)) },
                MagnifierDisplay(Rectangle(2, 2, 4, 4)) { BaseMultiResolutionImage(bitmap(2, 2, Color.BLUE)) },
            )
        }.capture(Rectangle(-2, -2, 6, 6))).image
        val layout = image.layout(Dimension(24, 20), TWO, CENTER)
        val rendered = render(image, layout)
        assertEquals(Point(6, 4), layout.imageOrigin)
        assertEquals(Color.WHITE.rgb, rendered.getRGB(6, 4))
        assertEquals(Color.BLACK.rgb, rendered.getRGB(12, 10))
        assertEquals(Color.BLUE.rgb, rendered.getRGB(16, 14))
    }

    @Test
    fun `capture reports outside unavailable and permission errors and supports retry`() {
        val area = Rectangle(-4, -4, 4, 4)
        assertEquals(Failure.OUTSIDE_DESKTOP, MagnifierCapture { emptyList() }.capture(area))
        val backend = MagnifierCapture {
            listOf(MagnifierDisplay(area) { error("Empty capture should not read pixels") })
        }
        assertEquals(Failure.OUTSIDE_DESKTOP, backend.capture(Rectangle(-3, -3, 0, 2)))
        assertEquals(Failure.UNAVAILABLE, MagnifierCapture { throw HeadlessException("No display") }.capture(area))
        assertEquals(Failure.UNAVAILABLE, MagnifierCapture {
            listOf(MagnifierDisplay(area) { throw AWTException("Robot unavailable") })
        }.capture(area))
        var denied = true
        val retry = MagnifierCapture {
            listOf(
                MagnifierDisplay(Rectangle(-4, -4, 2, 4)) { BaseMultiResolutionImage(bitmap(2, 4, Color.BLUE)) },
                MagnifierDisplay(Rectangle(-2, -4, 2, 4)) {
                    if (denied) throw SecurityException("Screen capture refused")
                    BaseMultiResolutionImage(bitmap(2, 4, Color.RED))
                },
            )
        }
        assertEquals(Failure.PERMISSION_DENIED, retry.capture(area))
        denied = false
        val frame = assertInstanceOf(Captured::class.java, retry.capture(area)).image
        val rendered = render(frame, frame.layout(Dimension(10, 10), ONE, UPPER_LEFT))
        assertEquals(Color.BLUE.rgb, rendered.getRGB(1, 1))
        assertEquals(Color.RED.rgb, rendered.getRGB(3, 1))
    }

    @Test
    fun `center alignment independently fits and scrolls axes with inverse coordinate mapping`() {
        val frame = MagnifierImage(Rectangle(10, 20, 40, 20), emptyList())
        val viewport = Dimension(40, 50)
        val center = frame.layout(viewport, TWO, CENTER)
        assertEquals(Dimension(80, 40), center.imageSize)
        assertEquals(Point(0, 5), center.imageOrigin)
        assertEquals(Point(20, 0), center.scrollPosition)
        assertEquals(Point2D.Double(6.5, 3.5), center.imagePoint(Point(13, 12)))
        val topLeft = frame.layout(viewport, TWO, UPPER_LEFT)
        assertEquals(Point(), topLeft.imageOrigin)
        assertEquals(Point(), topLeft.scrollPosition)
        assertEquals(Point2D.Double(6.5, 6.0), topLeft.imagePoint(Point(13, 12)))
        assertEquals(Point(0, 0), frame.layout(viewport, ONE, CENTER).scrollPosition)
        assertEquals(listOf(40, 60, 80, 100, 120, 140, 160),
            MagnifierZoom.entries.map { frame.layout(viewport, it, CENTER).imageSize.width })
    }

    @Test
    fun `annotations repaint on the same pixels at each zoom and clip to the captured image`() {
        val area = Rectangle(-8, 12, 10, 8)
        val frame = MagnifierImage(area, listOf(MagnifierTile(area, bitmap(10, 8, Color.WHITE))))
        val normal = frame.layout(Dimension(20, 16), ONE, CENTER)
        val stroke = MagnifierStroke(
            normal.imagePoint(Point(4, 7)), listOf(Point(10, 7), Point(13, 7)).map(normal::imagePoint), Color.RED, 2f,
        )
        val other = MagnifierStroke(Point2D.Double(6.0, 5.0), listOf(Point2D.Double(8.0, 5.0)), Color.BLUE, 1f)
        val small = render(frame, normal, listOf(stroke, other))
        val enlarged = frame.layout(Dimension(32, 24), FOUR, UPPER_LEFT)
        val large = render(frame, enlarged, listOf(stroke, other))
        assertEquals(Color.BLACK.rgb, small.getRGB(4, 7), "stroke is clipped before the image origin")
        assertEquals(Color.RED.rgb, small.getRGB(9, 7))
        assertEquals(Color.RED.rgb, large.getRGB(16, 12))
        assertEquals(Color.WHITE.rgb, large.getRGB(16, 15), "displayed stroke width remains constant")
        assertTrue(large.getRGB(28, 20) != Color.WHITE.rgb)
        assertEquals(Color.WHITE.rgb, render(frame, enlarged).getRGB(16, 12))
    }

    private fun render(frame: MagnifierImage, layout: MagnifierLayout, strokes: List<MagnifierStroke> = emptyList()): BufferedImage {
        val rendered = bitmap(100, 80, Color.BLACK)
        val graphics = rendered.createGraphics()
        try { frame.paint(graphics, layout, strokes) } finally { graphics.dispose() }
        return rendered
    }

    private fun bitmap(width: Int, height: Int, color: Color) = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also {
        val graphics = it.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
    }
}
