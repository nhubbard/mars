package edu.missouristate.mars.plugin.tools.xray

import androidx.compose.ui.unit.IntSize
import edu.missouristate.mars.datapath.DatapathDiagram.Wire
import edu.missouristate.mars.plugin.tools.toolsMessages
import java.awt.Color

/** The Release 6 source-level execution path, with no timing or pipeline assumptions. */
internal object XRayWideDiagram {
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
        XRayNode(x, y, width, 72, toolsMessages.getMessage("xray.mars64.$key"))

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
                toolsMessages.getMessage("xray.mars64.${wire.name}").replace('\n', ' '),
                wire.horizontal, wire.axis, wire.start, wire.end, false,
                if (wire.name == "nextPc" && instruction.isControlFlow) Color(0xe29848) else Color(0x559bd3),
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
