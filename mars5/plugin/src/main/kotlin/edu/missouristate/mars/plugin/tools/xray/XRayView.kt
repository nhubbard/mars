package edu.missouristate.mars.plugin.tools.xray

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.tools.MarsToolExecutionSelector
import edu.missouristate.mars.plugin.tools.scrollableToolPanel
import edu.missouristate.mars.plugin.tools.toolsMessages
import edu.missouristate.mars.util.Binary
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.Timer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.JewelComposePanel

internal class XRayView(project: Project) : Disposable {
    private val selector = MarsToolExecutionSelector(project) { refreshTrace(resetSelection = true) }
    private val history = ComboBox<StepEvent>().apply {
        renderer = textListCellRenderer(toolsMessages.getMessage("xray.noSteps")) {
            val decoded = InstructionDefaults.get().findByBinaryCode(it.instruction())
            val name = if (decoded == null) toolsMessages.getMessage("xray.unknown") else decoded.name
            toolsMessages.getMessage("xray.step", it.stepCount().toString(), Binary.intToHexString(it.pc()), name)
        }
    }
    private val unit = ComboBox(XRayUnit.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { toolsMessages.getMessage("xray.unit.${it.name}") }
    }
    private val speed = ComboBox(Rate.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { toolsMessages.getMessage("xray.rate.${it.name}") }
        selectedItem = Rate.NORMAL
        getAccessibleContext().accessibleName = toolsMessages.getMessage("xray.speed")
    }
    private val capture = JButton()
    private val clear = JButton(toolsMessages.getMessage("xray.clear"))
    private val play = JButton()
    private val reset = JButton(toolsMessages.getMessage("xray.reset"))
    private val descriptionText = mutableStateOf(toolsMessages.getMessage("xray.noSteps"))
    private val description = JewelComposePanel(content = xRayDetailsContent(descriptionText)).apply {
        preferredSize = JBUI.size(240, 112)
        getAccessibleContext().accessibleName = toolsMessages.getMessage("xray.description")
    }
    private val schematicFrame = mutableStateOf(XRaySchematicFrame(XRayUnit.DATAPATH))
    private val schematicPosition = mutableIntStateOf(0)
    private val schematic = JewelComposePanel(
        content = xRaySchematicContent(schematicFrame, schematicPosition) { unit.selectedItem = it },
    ).apply {
        preferredSize = JBUI.size(280, 200)
        accessibleContext.accessibleName = toolsMessages.getMessage("xray.schematic")
    }
    private val playback = XRayPlayback()
    private var steps = emptyList<StepEvent>()
    private var diagrams = emptyMap<XRayUnit, XRayDiagram>()
    private var currentStep: StepEvent? = null
    private var instruction: XRayInstruction? = null
    private val clock = Timer(30) {
        if (playback.advance((speed.selectedItem as Rate).ticks)) showPosition()
    }

    private val controls = panel {
        row {
            cell(selector.component).label(toolsMessages.getMessage("counter.selector"), LabelPosition.TOP)
                .align(AlignX.FILL).resizableColumn()
        }
        row {
            cell(capture)
            cell(clear)
        }
        row {
            cell(history).label(toolsMessages.getMessage("xray.instruction"), LabelPosition.TOP)
                .align(AlignX.FILL).resizableColumn()
        }
        row(toolsMessages.getMessage("xray.diagram")) { cell(unit) }
        row {
            cell(play)
            cell(reset)
        }
        row(toolsMessages.getMessage("xray.speed")) { cell(speed) }
        row { cell(schematic).align(Align.FILL).resizableColumn() }.resizableRow()
        row { cell(description).align(AlignX.FILL).resizableColumn() }
        row { comment(toolsMessages.getMessage("xray.help", XRayTrace.capacity)).resizableColumn() }
    }
    private val scrollBody = object : ScrollablePanel(BorderLayout()) {
        // Fill tall docks while preserving the diagram's preferred height in short docks.
        override fun getScrollableTracksViewportHeight() = parent.height > preferredSize.height
    }.apply { add(controls, BorderLayout.CENTER) }
    val component = scrollableToolPanel(this, scrollBody, emptyMap())

    init {
        capture.addActionListener {
            val trace = selector.process!!.xray
            trace.setCaptureEnabled(!trace.connected)
            refreshTrace()
        }
        clear.addActionListener {
            selector.process!!.xray.clear()
            refreshTrace()
        }
        history.addActionListener { selectInstruction() }
        unit.addActionListener { showDiagram() }
        play.addActionListener {
            playback.toggle()
            showPosition()
        }
        reset.addActionListener {
            playback.reset()
            showPosition()
        }
        showDiagram()
        refreshTrace()
    }

    private val refreshJob = component.launchOnShow("Refresh MIPS X-Ray") {
        if (diagrams.isEmpty()) {
            val loaded = withContext(Dispatchers.IO) { XRayUnit.entries.associateWith(XRayDiagram::load) }
            withContext(Dispatchers.EDT) {
                diagrams = loaded
                showDiagram()
            }
        }
        withContext(Dispatchers.EDT) { clock.start() }
        try {
            while (true) {
                withContext(Dispatchers.EDT) {
                    selector.refresh()
                    refreshTrace()
                }
                delay(200)
            }
        } finally {
            // launchOnShow runs on the UI dispatcher and cancels when the component is hidden.
            clock.stop()
        }
    }

    private fun refreshTrace(resetSelection: Boolean = false) {
        val trace = selector.process?.xray
        val snapshot = trace?.snapshot().orEmpty()
        capture.isEnabled = trace != null
        capture.text = toolsMessages.getMessage(if (trace?.connected == true) "counter.disconnect" else "counter.connect")
        clear.isEnabled = snapshot.isNotEmpty()
        if (snapshot === steps) return
        val previous = history.selectedItem as StepEvent?
        val reviewing = if (resetSelection || previous == steps.lastOrNull()) null else previous
        steps = snapshot
        history.model = DefaultComboBoxModel(snapshot.toTypedArray()).apply {
            selectedItem = reviewing?.takeIf(snapshot::contains) ?: snapshot.lastOrNull()
        }
        selectInstruction()
    }

    private fun selectInstruction() {
        val step = history.selectedItem as StepEvent?
        if (step == currentStep) return
        currentStep = step
        instruction = step?.let(::XRayInstruction)
        showDiagram()
    }

    private fun showDiagram() {
        val selectedUnit = unit.selectedItem as XRayUnit
        val diagram = diagrams[selectedUnit]
        val selectedInstruction = instruction
        val signals = selectedInstruction?.let {
            diagrams.getValue(selectedUnit).signals(it.word, it.format)
        }.orEmpty()
        schematicFrame.value = XRaySchematicFrame(selectedUnit, diagram?.wireSegments.orEmpty(), signals, selectedInstruction)
        playback.show(signals.maxOfOrNull(XRaySignal::endsAt) ?: 0)
        history.isEnabled = steps.isNotEmpty()
        play.isEnabled = signals.isNotEmpty()
        reset.isEnabled = play.isEnabled
        speed.isEnabled = play.isEnabled
        descriptionText.value = selectedInstruction?.let {
            val paths = signals.asSequence()
                .filter { signal -> !signal.isLabel && signal.color != inactiveSignal }
                .map { signal -> signal.name.trimStart('*').replace('_', ' ') }
                .distinct()
                .joinToString(", ")
            "${it.describe()}\n${toolsMessages.getMessage("xray.paths", paths)}"
        } ?: toolsMessages.getMessage("xray.noSteps")
        description.accessibleContext.accessibleDescription = descriptionText.value
        showPosition()
    }

    private fun showPosition() {
        play.text = toolsMessages.getMessage(if (playback.playing) "xray.pause" else "xray.play")
        schematicPosition.intValue = playback.position
    }

    override fun dispose() {
        clock.stop()
        refreshJob.cancel()
    }

    private enum class Rate(val ticks: Int) { SLOW(3), NORMAL(6), FAST(12) }

    private companion object {
        val inactiveSignal = Color(100, 100, 100)
    }
}
