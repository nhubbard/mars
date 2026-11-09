package edu.missouristate.mars.plugin.tools

import com.intellij.DynamicBundle
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Mode
import edu.missouristate.mars.plugin.tools.xray.XRayView
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JProgressBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class MarsToolsWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        fun addTab(name: String, component: JComponent, view: Disposable): Content {
            val content = ContentFactory.getInstance().createContent(component, toolsMessages.getMessage(name), false)
            content.setDisposer(view)
            toolWindow.contentManager.addContent(content)
            return content
        }
        val counter = InstructionCountsView(
            project, listOf("counter.r", "counter.i", "counter.j", "counter.other"), showBars = false,
            MarsProcessHandler::instructionCounter,
        )
        addTab("counter.name", counter.component, counter)
        val statistics = InstructionCountsView(
            project, InstructionStatistics.Category.entries.map { it.messageKey }, showBars = true,
            MarsProcessHandler::instructionStatistics,
        )
        addTab("statistics.name", statistics.component, statistics)
        val branchHistory = BranchHistoryView(project)
        addTab("branchHistory.name", branchHistory.component, branchHistory)
        val bitmap = MemoryGridView(project, Mode.BITMAP, MarsProcessHandler::bitmapDisplay)
        addTab(Mode.BITMAP.nameKey, bitmap.component, bitmap)
        val references = MemoryGridView(project, Mode.REFERENCES, MarsProcessHandler::memoryReferences)
        addTab(Mode.REFERENCES.nameKey, references.component, references)
        val xray = XRayView(project)
        addTab("xray.name", xray.component, xray)
        val cache = DataCacheView(project)
        addTab("cache.name", cache.component, cache)
        val floatingPoint = FloatingPointView(project)
        addTab("float.name", floatingPoint.component, floatingPoint)
        val digitalLab = DigitalLabView(project)
        addTab("digitalLab.name", digitalLab.component, digitalLab)
        val keyboardDisplay = KeyboardDisplayView(project)
        addTab("keyboardDisplay.name", keyboardDisplay.component, keyboardDisplay)
            .setPreferredFocusableComponent(keyboardDisplay.inputComponent)
    }
}

internal val toolsMessages = DynamicBundle(MarsToolsWindowFactory::class.java, "messages.MarsBundle")

internal class MarsToolExecutionSelector(private val project: Project, private val selectionChanged: () -> Unit) {
    private data class Execution(val id: Long, val name: String, val process: MarsProcessHandler)

    private val selector = ComboBox<Execution>().apply {
        renderer = textListCellRenderer(toolsMessages.getMessage("counter.empty")) {
            toolsMessages.getMessage("counter.execution", it.name, it.id.toString())
        }
        addActionListener { selectionChanged() }
    }
    val component: JComponent get() = selector
    val process: MarsProcessHandler? get() = (selector.selectedItem as Execution?)?.process
    private var executions = emptyList<Execution>()

    fun refresh() {
        val available = RunContentManager.getInstance(project).allDescriptors.mapNotNull { descriptor ->
            (descriptor.processHandler as? MarsProcessHandler)?.let {
                Execution(descriptor.executionId, descriptor.displayName, it)
            }
        }.sortedByDescending(Execution::id)
        if (available != executions) {
            val selected = selector.selectedItem
            executions = available
            val next = available.firstOrNull { it == selected } ?: available.firstOrNull()
            selector.model = DefaultComboBoxModel(available.toTypedArray()).apply { selectedItem = next }
            if (next != selected) selectionChanged()
        }
    }
}

private class InstructionCountsView(
    project: Project, categories: List<String>, showBars: Boolean,
    private val counts: (MarsProcessHandler) -> InstructionCounts,
) : Disposable {
    private val selector = MarsToolExecutionSelector(project, ::render)
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val total = JBLabel()
    private val labels = categories.map { JBLabel() }
    private val bars = if (showBars) categories.map { key ->
        JProgressBar(0, 100).apply { accessibleContext.accessibleName = toolsMessages.getMessage(key) }
    } else emptyList()
    private val emptySnapshot = InstructionCountsSnapshot(false, List(categories.size) { 0L })

    val component = panel {
        row(toolsMessages.getMessage("counter.selector")) {
            cell(selector.component).align(AlignX.FILL).resizableColumn()
        }
        row {
            cell(toggle)
            cell(reset)
        }
        row(toolsMessages.getMessage("counter.total")) { cell(total) }
        categories.forEachIndexed { index, key ->
            row(toolsMessages.getMessage(key)) {
                cell(labels[index])
                bars.getOrNull(index)?.let { cell(it).align(AlignX.FILL).resizableColumn() }
            }
        }
        row { comment(toolsMessages.getMessage("counter.reverse.policy")) }
    }

    init {
        toggle.addActionListener {
            val counter = counts(selector.process!!)
            counter.setConnected(!counter.countsSnapshot().connected)
            render()
        }
        reset.addActionListener {
            counts(selector.process!!).reset()
            render()
        }
        refresh()
    }

    private val refreshJob = component.launchOnShow("Refresh MARS instruction counts") {
        while (true) {
            withContext(Dispatchers.EDT) { refresh() }
            delay(200)
        }
    }

    private fun refresh() {
        selector.refresh()
        render()
    }

    private fun render() {
        val execution = selector.process
        val snapshot = execution?.let { counts(it).countsSnapshot() } ?: emptySnapshot
        toggle.isEnabled = execution != null
        reset.isEnabled = execution != null
        toggle.text = toolsMessages.getMessage(if (snapshot.connected) "counter.disconnect" else "counter.connect")
        total.text = snapshot.total.toString()
        for ((index, count) in snapshot.counts.withIndex()) {
            val percent = snapshot.percent(count)
            labels[index].text = toolsMessages.getMessage("counter.count", count.toString(), percent)
            bars.getOrNull(index)?.value = percent
        }
    }

    override fun dispose() {
        refreshJob.cancel()
    }
}
