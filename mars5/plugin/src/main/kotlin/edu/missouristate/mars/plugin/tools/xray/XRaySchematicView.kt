package edu.missouristate.mars.plugin.tools.xray

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.missouristate.mars.datapath.DatapathDiagram.Wire
import edu.missouristate.mars.plugin.tools.toolsMessages
import edu.missouristate.mars.plugin.tools.xray.XRayShape.ALU
import edu.missouristate.mars.plugin.tools.xray.XRayShape.AND
import edu.missouristate.mars.plugin.tools.xray.XRayShape.AND_DOWN
import edu.missouristate.mars.plugin.tools.xray.XRayShape.BLOCK
import edu.missouristate.mars.plugin.tools.xray.XRayShape.OR
import edu.missouristate.mars.plugin.tools.xray.XRayShape.OVAL
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.focusOutline

internal data class XRaySchematicFrame(
    val unit: XRayUnit,
    val wires: List<Wire> = emptyList(),
    val signals: List<XRaySignal> = emptyList(),
    val instruction: XRayInstruction? = null,
)

internal fun xRaySchematicContent(
    frameState: State<XRaySchematicFrame>, position: State<Int>, openUnit: (XRayUnit) -> Unit,
): @Composable () -> Unit = {
    val frame = frameState.value
    val schematic = xRaySchematics.getValue(frame.unit)
    val colors = JewelTheme.globalColors
    val ink = colors.text.normal
    val fill = lerp(colors.panelBackground, ink, 0.10f)
    val wireInk = ink.copy(alpha = 0.48f)
    val nodeInk = ink.copy(alpha = 0.78f)
    val componentPaths = remember(schematic) {
        schematic.nodes.map { node -> nodePath(node.shape, node.width.toFloat(), node.height.toFloat()) }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val factor = minOf(maxWidth.value / schematic.size.width, maxHeight.value / schematic.size.height)
        // Tool-window animation can measure the Compose host at zero width before opening it.
        if (factor <= 0f) return@BoxWithConstraints
        Box(Modifier.size((schematic.size.width * factor).dp, (schematic.size.height * factor).dp)) {
            Canvas(Modifier.matchParentSize()) {
                val zoom = size.width / schematic.size.width
                scale(zoom, pivot = Offset.Zero) {
                    for (wire in frame.wires) {
                        wireSegment(wire.horizontal(), wire.axis(), wire.start(), wire.end(), wireInk, 2f)
                    }
                    for (junction in schematic.junctions) {
                        drawCircle(wireInk, radius = 4f, center = Offset(junction.x.toFloat(), junction.y.toFloat()))
                    }
                    val time = position.value
                    for (signal in frame.signals) {
                        val end = signal.positionAt(time)
                        if (end != null && !signal.isLabel) {
                            val color = lerp(Color(signal.color.rgb), ink, 0.27f)
                            wireSegment(signal.horizontal, signal.axis, signal.start, end, color, 5f)
                        }
                    }
                    for ((index, node) in schematic.nodes.withIndex()) {
                        translate(node.x.toFloat(), node.y.toFloat()) {
                            val path = componentPaths[index]
                            drawPath(path, fill)
                            drawPath(path, nodeInk, style = Stroke(width = 2.5f))
                        }
                    }
                    for (inversion in schematic.inversions) {
                        val center = Offset(inversion.x.toFloat(), inversion.y.toFloat())
                        drawCircle(fill, radius = 4.5f, center = center)
                        drawCircle(nodeInk, radius = 4.5f, center = center, style = Stroke(width = 2f))
                    }
                }
            }
            for (node in schematic.nodes) {
                if (node.label.isNotEmpty()) SchematicNodeLabel(node, factor, openUnit)
            }
            for (port in schematic.ports) {
                Text(port.label, Modifier.offset((port.x * factor).dp, (port.y * factor).dp),
                    fontSize = (17 * factor).sp, fontWeight = FontWeight.SemiBold)
            }
            frame.instruction?.let { instruction ->
                val time = position.value
                for (signal in frame.signals) {
                    val at = signal.positionAt(time)
                    if (signal.isLabel && at != null && time < signal.endsAt) {
                        Text(instruction.signalLabel(signal.name), Modifier.offset((signal.axis * factor).dp, (at * factor).dp),
                            fontSize = (18 * factor).sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SchematicNodeLabel(node: XRayNode, factor: Float, openUnit: (XRayUnit) -> Unit) {
    val bounds = Modifier.offset((node.x * factor).dp, (node.y * factor).dp)
        .size((node.width * factor).dp, (node.height * factor).dp)
    val target = node.opens
    val modifier = if (target == null) bounds else {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        bounds.focusOutline(focused, RoundedCornerShape(3.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = toolsMessages.getMessage("xray.diagram.open", node.label)) { openUnit(target) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(node.label, fontSize = (15 * factor).sp, lineHeight = (18 * factor).sp,
            textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, softWrap = false)
    }
}

private fun DrawScope.wireSegment(horizontal: Boolean, axis: Int, start: Int, end: Int, color: Color, width: Float) {
    val first = if (horizontal) Offset(start.toFloat(), axis.toFloat()) else Offset(axis.toFloat(), start.toFloat())
    val last = if (horizontal) Offset(end.toFloat(), axis.toFloat()) else Offset(axis.toFloat(), end.toFloat())
    drawLine(color, first, last, strokeWidth = width, cap = StrokeCap.Round)
}

private fun nodePath(shape: XRayShape, width: Float, height: Float): Path = Path().apply {
    when (shape) {
        BLOCK -> addRoundRect(RoundRect(Rect(0f, 0f, width, height), 5f, 5f))
        OVAL -> addOval(Rect(0f, 0f, width, height))
        AND -> {
            moveTo(0f, 0f)
            lineTo(width / 2, 0f)
            cubicTo(width * 1.17f, 0f, width * 1.17f, height, width / 2, height)
            lineTo(0f, height)
            close()
        }
        AND_DOWN -> {
            moveTo(0f, 0f)
            lineTo(width, 0f)
            lineTo(width, height / 2)
            cubicTo(width, height * 1.17f, 0f, height * 1.17f, 0f, height / 2)
            close()
        }
        OR -> {
            moveTo(0f, 0f)
            cubicTo(width * 0.60f, 0f, width * 0.91f, height * 0.15f, width, height / 2)
            cubicTo(width * 0.91f, height * 0.85f, width * 0.60f, height, 0f, height)
            quadraticTo(width * 0.29f, height / 2, 0f, 0f)
            close()
        }
        ALU -> {
            moveTo(0f, 0f)
            lineTo(width, height * 0.30f)
            lineTo(width, height * 0.70f)
            lineTo(0f, height)
            lineTo(0f, height * 0.62f)
            lineTo(width * 0.24f, height / 2)
            lineTo(0f, height * 0.38f)
            close()
        }
    }
}
