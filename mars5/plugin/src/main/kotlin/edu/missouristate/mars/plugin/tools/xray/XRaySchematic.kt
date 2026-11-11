package edu.missouristate.mars.plugin.tools.xray

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import edu.missouristate.mars.plugin.tools.xray.XRayUnit.ALU_CONTROL
import edu.missouristate.mars.plugin.tools.xray.XRayUnit.CONTROL
import edu.missouristate.mars.plugin.tools.xray.XRayUnit.DATAPATH
import edu.missouristate.mars.plugin.tools.xray.XRayUnit.REGISTERS

internal enum class XRayShape { BLOCK, OVAL, AND, AND_DOWN, OR, ALU }

internal data class XRayNode(
    val x: Int, val y: Int, val width: Int, val height: Int,
    val label: String, val shape: XRayShape = XRayShape.BLOCK, val opens: XRayUnit? = null,
)

internal data class XRayPort(val x: Int, val y: Int, val label: String)

internal data class XRaySchematic(
    val size: IntSize, val nodes: List<XRayNode>, val ports: List<XRayPort> = emptyList(),
    val junctions: List<IntOffset> = emptyList(), val inversions: List<IntOffset> = emptyList(),
)

// Coordinates are in the shared datapath table's diagram units. Ports and blocks use the same
// coordinate system as their wires, so the renderer can scale them as one schematic.
internal val xRaySchematics = mapOf(
    DATAPATH to XRaySchematic(IntSize(1000, 574), listOf(
        XRayNode(121, 343, 40, 40, "PC"),
        XRayNode(225, 319, 88, 90, "Instruction\nmemory"),
        XRayNode(264, 80, 46, 83, "+", XRayShape.ALU),
        XRayNode(392, 52, 46, 50, "≪ 2", XRayShape.OVAL),
        XRayNode(356, 179, 62, 104, "Control", XRayShape.OVAL, CONTROL),
        XRayNode(425, 294, 97, 134, "Registers", opens = REGISTERS),
        XRayNode(371, 365, 21, 48, "M", XRayShape.OVAL),
        XRayNode(439, 435, 61, 66, "Sign\nextend", XRayShape.OVAL),
        XRayNode(557, 365, 21, 49, "M", XRayShape.OVAL),
        XRayNode(561, 454, 64, 68, "ALU\ncontrol", XRayShape.OVAL, ALU_CONTROL),
        XRayNode(605, 300, 64, 115, "ALU", XRayShape.ALU),
        XRayNode(724, 317, 88, 91, "Data\nmemory"),
        XRayNode(847, 342, 21, 49, "M", XRayShape.OVAL),
        XRayNode(561, 131, 48, 51, "≪ 2", XRayShape.OVAL),
        XRayNode(662, 104, 47, 84, "+", XRayShape.ALU),
        XRayNode(728, 182, 17, 24, "", XRayShape.AND),
        XRayNode(769, 97, 21, 51, "M", XRayShape.OVAL),
        XRayNode(814, 97, 21, 51, "M", XRayShape.OVAL),
    ), listOf(XRayPort(223, 123, "4"), XRayPort(632, 327, "Zero")), junctions = listOf(
        IntOffset(193, 362), IntOffset(320, 380), IntOffset(330, 337), IntOffset(330, 359),
        IntOffset(330, 400), IntOffset(330, 467), IntOffset(350, 359), IntOffset(498, 77),
        IntOffset(498, 121), IntOffset(528, 380), IntOffset(537, 401), IntOffset(601, 121), IntOffset(694, 364),
    )),
    REGISTERS to XRaySchematic(IntSize(800, 480), listOf(
        XRayNode(111, 92, 123, 153, "Write\ndecoder"),
        XRayNode(666, 94, 72, 174, "Read\nMUX", XRayShape.OVAL),
        XRayNode(666, 291, 72, 175, "Read\nMUX", XRayShape.OVAL),
    ) + (listOf("Register 1", "Register 2", "…", "Register 31", "Register 32").mapIndexed { index, label ->
        XRayNode(377, 105 + index * 30, 143, 30, label)
    }) + List(5) { index -> XRayNode(314, 99 + index * 31, 25, 24, "", XRayShape.AND) }, listOf(
        XRayPort(6, 44, "Read address 1"), XRayPort(6, 251, "Read address 2"),
        XRayPort(6, 323, "Write address"), XRayPort(6, 405, "Write data"),
        XRayPort(322, 8, "Write enable"),
    ), junctions = listOf(
        IntOffset(258, 117), IntOffset(258, 149), IntOffset(258, 180), IntOffset(258, 212),
        IntOffset(274, 159), IntOffset(274, 221), IntOffset(549, 243), IntOffset(571, 213),
        IntOffset(595, 183), IntOffset(618, 152), IntOffset(640, 122),
    )),
    CONTROL to XRaySchematic(IntSize(800, 498),
        listOf(209, 313, 409, 504).map { XRayNode(it, 169, 57, 56, "", XRayShape.AND_DOWN) } + listOf(
            XRayNode(567, 245, 74, 52, "", XRayShape.OR),
            XRayNode(572, 324, 75, 51, "", XRayShape.OR),
        ), List(6) { index -> XRayPort(4, 7 + index * 24, "Bit ${5 - index}") } + listOf(
            XRayPort(704, 213, "RegDst"), XRayPort(704, 247, "ALUSrc"),
            XRayPort(704, 284, "MemToReg"), XRayPort(704, 321, "WriteReg"),
            XRayPort(704, 361, "ReadMem"), XRayPort(704, 384, "WriteMem"),
            XRayPort(704, 405, "Branch"), XRayPort(704, 427, "ALUOp1"),
            XRayPort(704, 450, "ALUOp0"),
        ), junctions = listOf(
            IntOffset(237, 240), IntOffset(237, 337), IntOffset(237, 450), IntOffset(341, 257),
            IntOffset(341, 309), IntOffset(341, 360), IntOffset(341, 385), IntOffset(437, 285),
            IntOffset(437, 405), IntOffset(533, 426), IntOffset(533, 471),
        ) + listOf(212, 317, 413, 508).flatMap { x ->
            List(6) { bit -> IntOffset(x + bit * 10, 31 + bit * 23) }
        }, inversions = listOf(212, 222, 232, 242, 252, 262, 319, 329, 339, 349, 412, 423, 444,
            507, 517, 527, 537, 547, 557).map { IntOffset(it, 165) }),
    ALU_CONTROL to XRaySchematic(IntSize(800, 457), listOf(
        XRayNode(299, 124, 79, 81, "", XRayShape.AND),
        XRayNode(302, 289, 79, 83, "", XRayShape.AND),
        XRayNode(555, 101, 105, 73, "", XRayShape.OR),
        XRayNode(564, 212, 104, 73, "", XRayShape.OR),
        XRayNode(147, 317, 107, 74, "", XRayShape.OR),
    ), listOf(
        XRayPort(165, 4, "ALUOp1"), XRayPort(314, 4, "ALUOp0"),
        XRayPort(5, 76, "Bit 3"), XRayPort(5, 151, "Bit 2"),
        XRayPort(5, 255, "Bit 1"), XRayPort(5, 338, "Bit 0"),
        XRayPort(742, 105, "Op 1"), XRayPort(742, 215, "Op 2"), XRayPort(742, 298, "Op 3"),
    ), junctions = listOf(IntOffset(240, 148), IntOffset(240, 232)),
        inversions = listOf(IntOffset(568, 232), IntOffset(570, 263))),
)
