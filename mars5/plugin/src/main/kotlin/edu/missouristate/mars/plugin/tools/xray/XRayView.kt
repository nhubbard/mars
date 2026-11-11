package edu.missouristate.mars.plugin.tools.xray

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.tools.MarsToolRunContent
import edu.missouristate.mars.plugin.tools.marsToolExecutions
import edu.missouristate.mars.plugin.tools.toolsMessages
import edu.missouristate.mars.util.Binary
import java.awt.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.JewelComposePanel

internal class XRayView(private val project: Project) : Disposable {
    private val playback = XRayPlayback()
    private val diagrams by lazy { XRayUnit.entries.associateWith(XRayDiagram::load) }

    var executions by mutableStateOf(emptyList<MarsToolRunContent>())
        private set
    var execution by mutableStateOf<MarsToolRunContent?>(null)
        private set
    var connected by mutableStateOf(false)
        private set
    var steps by mutableStateOf(emptyList<StepEvent>(), referentialEqualityPolicy())
        private set
    var step by mutableStateOf<StepEvent?>(null)
        private set
    var unit by mutableStateOf(XRayUnit.DATAPATH)
        private set
    var rate by mutableStateOf(Rate.NORMAL)
        private set
    var playing by mutableStateOf(false)
        private set

    private var instruction: XRayInstruction? = null
    private val description = mutableStateOf(toolsMessages.getMessage("xray.noSteps"))
    private val schematicFrame = mutableStateOf(XRaySchematicFrame(unit, diagrams.getValue(unit).wireSegments))
    private val position = mutableIntStateOf(0)
    val canPlay get() = schematicFrame.value.signals.isNotEmpty()

    private val schematic = xRaySchematicContent(schematicFrame, position, ::selectUnit)
    private val details = xRayDetailsContent(description)
    val content = xRayPanelContent(this, schematic, details)
    val component = JewelComposePanel(content = content).apply {
        accessibleContext.accessibleName = toolsMessages.getMessage("xray.name")
    }

    private val refreshJob = component.launchOnShow("Refresh MIPS X-Ray") {
        launch {
            while (true) {
                delay(30)
                if (playback.advance(rate.ticks)) showPosition()
            }
        }
        while (true) {
            refreshExecutions()
            refreshTrace()
            delay(200)
        }
    }

    private fun refreshExecutions() {
        val available = marsToolExecutions(project)
        if (available == executions) return
        executions = available
        val selected = execution
        if (selected !in available) selectExecution(available.firstOrNull())
    }

    fun selectExecution(selected: MarsToolRunContent?) {
        execution = selected
        refreshTrace(resetSelection = true)
    }

    fun toggleCapture() {
        val trace = execution!!.process.xray
        trace.setCaptureEnabled(!trace.connected)
        refreshTrace()
    }

    fun clearTrace() {
        execution!!.process.xray.clear()
        refreshTrace()
    }

    fun selectStep(selected: StepEvent?) {
        if (selected == step) return
        step = selected
        instruction = selected?.let(::XRayInstruction)
        showDiagram()
    }

    fun selectUnit(selected: XRayUnit) {
        unit = selected
        showDiagram()
    }

    fun selectRate(selected: Rate) {
        rate = selected
    }

    fun togglePlayback() {
        playback.toggle()
        showPosition()
    }

    fun resetPlayback() {
        playback.reset()
        showPosition()
    }

    private fun refreshTrace(resetSelection: Boolean = false) {
        val trace = execution?.process?.xray
        val snapshot = trace?.snapshot().orEmpty()
        connected = trace?.connected == true
        if (snapshot === steps) return
        val previous = step
        val reviewing = if (resetSelection || previous == steps.lastOrNull()) null else previous
        steps = snapshot
        selectStep(reviewing?.takeIf(snapshot::contains) ?: snapshot.lastOrNull())
    }

    private fun showDiagram() {
        val diagram = diagrams.getValue(unit)
        val selected = instruction
        val signals = selected?.let { diagram.signals(it.word, it.format) }.orEmpty()
        schematicFrame.value = XRaySchematicFrame(unit, diagram.wireSegments, signals, selected)
        playback.show(signals.maxOfOrNull(XRaySignal::endsAt) ?: 0)
        description.value = selected?.let {
            val paths = signals.asSequence()
                .filter { signal -> !signal.isLabel && signal.color != inactiveSignal }
                .map { signal -> signal.name.trimStart('*').replace('_', ' ') }
                .distinct()
                .joinToString(", ")
            "${it.describe()}\n${toolsMessages.getMessage("xray.paths", paths)}"
        } ?: toolsMessages.getMessage("xray.noSteps")
        showPosition()
    }

    private fun showPosition() {
        playing = playback.playing
        position.intValue = playback.position
    }

    override fun dispose() {
        refreshJob.cancel()
    }

    enum class Rate(val ticks: Int) { SLOW(3), NORMAL(6), FAST(12) }

    private companion object {
        val inactiveSignal = Color(100, 100, 100)
    }
}

internal fun StepEvent.xRayLabel(): String {
    val decoded = InstructionDefaults.get().findByBinaryCode(instruction())
    val name = decoded?.name ?: toolsMessages.getMessage("xray.unknown")
    return toolsMessages.getMessage("xray.step", stepCount().toString(), Binary.intToHexString(pc()), name)
}
