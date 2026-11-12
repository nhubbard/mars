@file:Suppress("FunctionName")

package edu.missouristate.mars.plugin.tools.xray

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import edu.missouristate.mars.plugin.tools.MarsToolRunContent
import edu.missouristate.mars.plugin.tools.toolsMessages
import edu.missouristate.mars.plugin.tools.xray.XRayView.Rate
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

internal fun xRayPanelContent(
    view: XRayView, schematic: @Composable () -> Unit, details: @Composable () -> Unit,
): @Composable () -> Unit = {
    VerticallyScrollableContainer(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val controlWidth = Modifier.widthIn(max = 270.dp).fillMaxWidth()
            val controlSpacing = Arrangement.spacedBy(6.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.widthIn(max = 220.dp).fillMaxWidth(), verticalArrangement = controlSpacing) {
                    val executions = view.executions
                    val executionLabels = remember(executions) { executions.map(MarsToolRunContent::label) }
                    XRayChoice("counter.selector", executionLabels, executions.indexOf(view.execution),
                        "counter.empty", executions.isNotEmpty()) {
                        view.selectExecution(executions[it])
                    }
                    FlowRow(horizontalArrangement = controlSpacing, verticalArrangement = controlSpacing) {
                        XRayButton(if (view.connected) "counter.disconnect" else "counter.connect", view.execution != null, view::toggleCapture)
                        XRayButton("xray.clear", view.steps.isNotEmpty(), view::clearTrace)
                    }
                }
                Column(controlWidth, verticalArrangement = controlSpacing) {
                    val steps = view.steps
                    val current = steps.indexOf(view.step)
                    val stepLabels = remember(steps) { steps.map { it.xRayLabel() } }
                    XRayChoice("xray.instruction", stepLabels, current, "xray.timeline.empty", steps.isNotEmpty()) {
                        view.selectStep(steps[it])
                    }
                    FlowRow(horizontalArrangement = controlSpacing, verticalArrangement = controlSpacing) {
                        XRayButton("xray.timeline.previous", current > 0) { view.selectStep(steps[current - 1]) }
                        XRayButton("xray.timeline.next", current + 1 < steps.size) { view.selectStep(steps[current + 1]) }
                        Text(toolsMessages.getMessage("xray.timeline.position", current + 1, steps.size))
                    }
                }
                Column(controlWidth, verticalArrangement = controlSpacing) {
                    XRayChoice("xray.diagram", XRayUnit.entries.map { toolsMessages.getMessage("xray.unit.${it.name}") },
                        view.unit.ordinal, "xray.diagram", true, Modifier.widthIn(max = 184.dp)) {
                        view.selectUnit(XRayUnit.entries[it])
                    }
                    FlowRow(horizontalArrangement = controlSpacing, verticalArrangement = controlSpacing) {
                        XRayButton(if (view.playing) "xray.pause" else "xray.play", view.canPlay, view::togglePlayback)
                        XRayButton("xray.reset", view.canPlay, view::resetPlayback)
                    }
                }
                Column(Modifier.widthIn(max = 128.dp).fillMaxWidth()) {
                    XRayChoice("xray.speed", Rate.entries.map { toolsMessages.getMessage("xray.rate.${it.name}") },
                        view.rate.ordinal, "xray.speed", view.canPlay) {
                        view.selectRate(Rate.entries[it])
                    }
                }
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val label = toolsMessages.getMessage("xray.schematic")
                Box(Modifier.fillMaxWidth().height(maxOf(200.dp, maxWidth / 1.7f))
                    .semantics { contentDescription = label }) {
                    schematic()
                }
            }
            val detailsLabel = toolsMessages.getMessage("xray.description")
            Box(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = detailsLabel }) { details() }
            InfoText(toolsMessages.getMessage("xray.help", XRayTrace.CAPACITY))
        }
    }
}

@Composable
private fun XRayChoice(
    key: String, options: List<String>, index: Int, empty: String, enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelection: (Int) -> Unit,
) {
    val label = toolsMessages.getMessage(key)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label)
        ListComboBox(
            items = options.ifEmpty { listOf(toolsMessages.getMessage(empty)) },
            selectedIndex = index.coerceAtLeast(0),
            onSelectedItemChange = onSelection,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            enabled = enabled,
        )
    }
}

@Composable
private fun XRayButton(key: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled) { Text(toolsMessages.getMessage(key)) }
}
