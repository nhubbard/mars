package edu.missouristate.mars.plugin.tools.xray

import androidx.compose.ui.unit.IntSize
import com.intellij.ui.JBColor
import edu.missouristate.mars.datapath.DatapathDiagram.Wire
import edu.missouristate.mars.plugin.tools.toolsMessages

/** The Release 6 source-level execution path, with no timing or pipeline assumptions. */
internal object XRayWideDiagram {
    private val labels by lazy {
        mapOf(
            "fetch" to toolsMessages.getMessage("xray.mars64.fetch"),
            "decode" to toolsMessages.getMessage("xray.mars64.decode"),
            "execute" to toolsMessages.getMessage("xray.mars64.execute"),
            "nextPc" to toolsMessages.getMessage("xray.mars64.nextPc"),
            "registers" to toolsMessages.getMessage("xray.mars64.registers"),
            "memory" to toolsMessages.getMessage("xray.mars64.memory"),
        )
    }
    private val signalColor = JBColor(0x236998, 0x559bd3)
    private val branchColor = JBColor(0x9f570f, 0xe29848)

    private val schematic by lazy {
        XRaySchematic(IntSize(960, 320), listOf(
            node(25, 85, 160, "fetch"),
            node(235, 85, 175, "decode"),
            node(480, 85, 170, "execute"),
            node(720, 85, 195, "nextPc"),
            node(195, 218, 255, "registers"),
            node(480, 218, 170, "memory"),
        ))
    }

    private fun node(x: Int, y: Int, width: Int, key: String) =
        XRayNode(x, y, width, 72, labels.getValue(key))

    private val wires = listOf(
        wire("fetch", true, 121, 185, 235),
        wire("execute", true, 121, 410, 480),
        wire("nextPc", true, 121, 650, 720),
        wire("registers", false, 322, 157, 218),
        wire("memory", false, 565, 157, 218),
    )

    private fun wire(key: String, horizontal: Boolean, axis: Int, start: Int, end: Int) =
        Wire(key, horizontal, axis, start, end, false, emptyList(), emptyList())

    fun frame(instruction: XRayInstruction): XRaySchematicFrame {
        val active = listOf(
            true, instruction.decoded, instruction.decoded,
            instruction.registerBanks.isNotEmpty(), instruction.hasMemoryTraffic,
        )
        val signals = wires.mapIndexedNotNull { index, wire ->
            if (!active[index]) return@mapIndexedNotNull null
            XRaySignal(
                labels.getValue(wire.name).replace('\n', ' '),
                wire.horizontal, wire.axis, wire.start, wire.end, false,
                if (wire.name == "nextPc" && instruction.isControlFlow) branchColor else signalColor,
                when (wire.name) {
                    "fetch" -> 0
                    "execute", "registers" -> 50
                    else -> 120
                },
            )
        }
        return XRaySchematicFrame(XRayUnit.DATAPATH, wires, signals, instruction, schematic)
    }
}
