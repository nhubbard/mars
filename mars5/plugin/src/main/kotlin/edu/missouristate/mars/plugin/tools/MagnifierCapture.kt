package edu.missouristate.mars.plugin.tools

import java.awt.AWTException
import java.awt.HeadlessException
import java.awt.Image
import java.awt.Rectangle
import java.awt.image.MultiResolutionImage

/** A display's logical desktop bounds and its native capture operation. */
internal data class MagnifierDisplay(val bounds: Rectangle, val read: (Rectangle) -> MultiResolutionImage)

internal class MagnifierCapture(private val displays: () -> List<MagnifierDisplay>) {
    sealed interface Result {
        data class Captured(val image: MagnifierImage) : Result
        enum class Failure : Result { OUTSIDE_DESKTOP, UNAVAILABLE, PERMISSION_DENIED }
    }

    fun capture(area: Rectangle): Result = try {
        val tiles = displays().mapNotNull { display ->
            val section = display.bounds.intersection(area)
            if (section.isEmpty) null else MagnifierTile(section, display.read(Rectangle(section)).resolutionVariants.last())
        }
        if (tiles.isEmpty()) Result.Failure.OUTSIDE_DESKTOP
        else Result.Captured(MagnifierImage(Rectangle(area), tiles))
    } catch (_: HeadlessException) {
        Result.Failure.UNAVAILABLE
    } catch (_: AWTException) {
        Result.Failure.UNAVAILABLE
    } catch (_: SecurityException) {
        Result.Failure.PERMISSION_DENIED
    }
}

/** The bitmap can have more physical pixels than its logical desktop bounds. */
internal data class MagnifierTile(val bounds: Rectangle, val image: Image)
