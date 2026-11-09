package edu.missouristate.mars.plugin.tools.xray

import com.intellij.openapi.util.JDOMUtil
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.InputStream
import java.util.PriorityQueue
import javax.imageio.ImageIO
import kotlin.math.abs

internal enum class XRayUnit(val xml: String, val image: String) {
    DATAPATH("MipsXRayOpcode", "datapath"),
    REGISTERS("registerDatapath", "register"),
    CONTROL("controlDatapath", "control"),
    ALU_CONTROL("ALUcontrolDatapath", "ALUcontrol"),
}

/** Pixel coordinates and time are in diagram units. The renderer chooses their scale and playback speed. */
internal data class XRaySignal(
    val name: String, val horizontal: Boolean, val axis: Int, val start: Int, val end: Int,
    val isLabel: Boolean, val color: Color, val startsAt: Int,
) {
    val endsAt = startsAt + abs(end - start)

    fun positionAt(time: Int): Int? {
        if (time < startsAt) return null
        val distance = minOf(time - startsAt, endsAt - startsAt)
        return start + distance * Integer.signum(end - start)
    }
}

internal class XRayDiagram private constructor(
    private val unit: XRayUnit, val image: BufferedImage, private val segments: List<Segment>,
    private val colors: Map<Palette, List<Color>>,
) {
    private data class Segment(
        val name: String, val horizontal: Boolean, val axis: Int, val start: Int, val end: Int,
        val isLabel: Boolean, val targets: List<Int>,
    )

    private data class Arrival(val index: Int, val time: Int)

    private enum class Palette(val tag: String) {
        REGISTER("color_Rtype"), IMMEDIATE("color_Itype"), JUMP("color_Jtype"),
        LOAD("color_LOADtype"), STORE("color_STOREtype"), BRANCH("color_BRANCHtype"),
        ALU_ADD("ALU_out010"), ALU_SUBTRACT("ALU_out110"), ALU_AND("ALU_out000"),
        ALU_OR("ALU_out001"), ALU_OTHER("ALU_out111"),
    }

    // Several wires converge or feed back into the diagram. Each begins on its earliest arrival.
    private val arrivalTimes: List<Arrival> = buildList {
        val visited = BooleanArray(segments.size)
        val pending = PriorityQueue(compareBy(Arrival::time, Arrival::index))
        pending.add(Arrival(0, 0))
        while (pending.isNotEmpty()) {
            val arrival = pending.remove()
            if (visited[arrival.index]) continue
            visited[arrival.index] = true
            add(arrival)
            val segment = segments[arrival.index]
            val nextTime = arrival.time + abs(segment.end - segment.start)
            for (target in segment.targets) pending.add(Arrival(target, nextTime))
        }
    }

    fun signals(word: Int, format: BasicInstructionFormat?): List<XRaySignal> {
        val palette = colors.getValue(selectPalette(word, format))
        return arrivalTimes.map { (index, time) ->
            val (name, horizontal, axis, start, end, isLabel) = segments[index]
            XRaySignal(name, horizontal, axis, start, end, isLabel, palette[index], time)
        }
    }

    private fun selectPalette(word: Int, format: BasicInstructionFormat?): Palette {
        return when (word ushr 26) {
            0 -> if (unit == XRayUnit.ALU_CONTROL) when (word and 0xf) {
                0 -> Palette.ALU_ADD
                2 -> Palette.ALU_SUBTRACT
                4 -> Palette.ALU_AND
                5 -> Palette.ALU_OR
                else -> Palette.ALU_OTHER
            } else Palette.REGISTER
            2, 3 -> Palette.JUMP
            in 0x20..0x27, 0x30, 0x31, 0x35 -> Palette.LOAD
            in 0x28..0x2f, 0x38, 0x39, 0x3d -> Palette.STORE
            else -> if (format == I_BRANCH_FORMAT) Palette.BRANCH else Palette.IMMEDIATE
        }
    }

    companion object {
        fun load(unit: XRayUnit): XRayDiagram {
            val image = resource("images/${unit.image}.png").use { ImageIO.read(it) }
            return resource("${unit.xml}.xml").use { read(unit, image, it) }
        }

        internal fun read(unit: XRayUnit, image: BufferedImage, xml: InputStream): XRayDiagram {
            val root = JDOMUtil.load(xml)
            val map = root.getChild("datapath_map")
            fun column(tag: String) = map.getChildren(tag).map { it.textTrim }
            val indices = column("num_vertex").map(String::toInt)
            val names = column("name")
            val horizontal = column("isMovingXaxis").map(String::toBooleanStrict)
            val axes = column("other_axis").map(String::toInt)
            val starts = column("init").map(String::toInt)
            val ends = column("end").map(String::toInt)
            val labels = column("is_text").map(String::toBooleanStrict)
            val targets = column("target_vertex").map { field -> field.split('#').map(String::toInt) }
            check(indices == indices.indices.toList()) { "Invalid vertex IDs in ${unit.xml}" }
            val segments = indices.map {
                Segment(names[it], horizontal[it], axes[it], starts[it], ends[it], labels[it], targets[it])
            }
            val colors = Palette.entries.filter { unit == XRayUnit.ALU_CONTROL || !it.tag.startsWith("ALU_") }
                .associateWith { palette ->
                    column(palette.tag).map { field ->
                        val (red, green, blue) = field.split('#').map(String::toInt)
                        Color(red, green, blue)
                    }
                }
            return XRayDiagram(unit, image, segments, colors)
        }

        private fun resource(name: String): InputStream =
            XRayDiagram::class.java.getResourceAsStream("/datapath/$name")
    }
}
