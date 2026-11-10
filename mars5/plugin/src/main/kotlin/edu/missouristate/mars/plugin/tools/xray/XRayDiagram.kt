package edu.missouristate.mars.plugin.tools.xray

import edu.missouristate.mars.datapath.DatapathDiagram
import edu.missouristate.mars.datapath.DatapathDiagram.Kind
import edu.missouristate.mars.datapath.DatapathDiagram.Palette
import edu.missouristate.mars.datapath.DatapathDiagram.Wire
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.PriorityQueue
import javax.imageio.ImageIO
import kotlin.math.abs

internal enum class XRayUnit(val kind: Kind, val image: String) {
    DATAPATH(Kind.MAIN, "datapath"),
    REGISTERS(Kind.REGISTERS, "register"),
    CONTROL(Kind.CONTROL, "control"),
    ALU_CONTROL(Kind.ALU_CONTROL, "ALUcontrol"),
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
    private val unit: XRayUnit, val image: BufferedImage, private val wires: List<Wire>,
) {
    private data class Arrival(val index: Int, val time: Int)

    private val colors = Palette.values().take(wires.first().colors().size).associateWith { palette ->
        wires.map { Color(it.rgb(palette)) }
    }

    // Several wires converge or feed back into the diagram. Each begins on its earliest arrival.
    private val arrivalTimes: List<Arrival> = buildList {
        val visited = BooleanArray(wires.size)
        val pending = PriorityQueue(compareBy(Arrival::time, Arrival::index))
        pending.add(Arrival(0, 0))
        while (pending.isNotEmpty()) {
            val arrival = pending.remove()
            if (visited[arrival.index]) continue
            visited[arrival.index] = true
            add(arrival)
            val wire = wires[arrival.index]
            val nextTime = arrival.time + abs(wire.end() - wire.start())
            for (target in wire.targets()) pending.add(Arrival(target, nextTime))
        }
    }

    fun signals(word: Int, format: BasicInstructionFormat?): List<XRaySignal> {
        val palette = colors.getValue(selectPalette(word, format))
        return arrivalTimes.map { (index, time) ->
            val wire = wires[index]
            XRaySignal(wire.name(), wire.horizontal(), wire.axis(), wire.start(), wire.end(), wire.label(), palette[index], time)
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
            val image = XRayDiagram::class.java.getResourceAsStream("/datapath/images/${unit.image}.png").use { ImageIO.read(it) }
            return XRayDiagram(unit, image, DatapathDiagram.forKind(unit.kind).wires())
        }
    }
}
