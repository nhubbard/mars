package edu.missouristate.mars.plugin.tools

import com.intellij.DynamicBundle
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.observable.util.whenFocusGained
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.launchOnShow
import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Mode
import edu.missouristate.mars.plugin.tools.xray.XRayView
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class MarsToolsWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = toolWindow.contentManager
        fun addTab(name: String, initialize: (Content) -> Unit) {
            val content = ContentFactory.getInstance().createContent(JBLabel(), toolsMessages.getMessage(name), false)
            content.initializeMarsToolOnSelection(initialize)
            manager.addContent(content)
        }
        fun <T : Disposable> addView(name: String, component: (T) -> JComponent, create: () -> T) {
            addTab(name) { content ->
                val view = create()
                content.setDisposer(view)
                content.component = component(view)
            }
        }
        addView("counter.name", InstructionCountsView::component) {
            InstructionCountsView(
                project, listOf("counter.r", "counter.i", "counter.j", "counter.other"), showBars = false,
                MarsProcessHandler::instructionCounter,
            )
        }
        addView("statistics.name", InstructionCountsView::component) {
            InstructionCountsView(
                project, InstructionStatistics.Category.entries.map { it.messageKey }, showBars = true,
                MarsProcessHandler::instructionStatistics,
            )
        }
        addView("branchHistory.name", BranchHistoryView::component) { BranchHistoryView(project) }
        addView(Mode.BITMAP.nameKey, MemoryGridView::component) { MemoryGridView(project, Mode.BITMAP, MarsProcessHandler::bitmapDisplay) }
        addView(Mode.REFERENCES.nameKey, MemoryGridView::component) {
            MemoryGridView(project, Mode.REFERENCES, MarsProcessHandler::memoryReferences)
        }
        addView("xray.name", XRayView::component) { XRayView(project) }
        addView("cache.name", DataCacheView::component) { DataCacheView(project) }
        addView("float.name", FloatingPointView::component) { FloatingPointView(project) }
        addView("digitalLab.name", DigitalLabView::component) { DigitalLabView(project) }
        addTab("keyboardDisplay.name") { content ->
            val view = KeyboardDisplayView(project)
            content.setDisposer(view)
            content.component = view.component
            content.setPreferredFocusableComponent(view.inputComponent)
        }
        addView("marsBot.name", MarsBotView::component) { MarsBotView(project) }
        addView("scavenger.name", ScavengerHuntView::component) { ScavengerHuntView(project) }
        MarsToolExtensions(project, manager, toolWindow.disposable)
    }
}

internal val toolsMessages = DynamicBundle(MarsToolsWindowFactory::class.java, "messages.MarsBundle")

internal fun scrollableToolPanel(
    owner: Disposable,
    body: JComponent,
    focusTargets: Map<JComponent, JComponent>,
) = ScrollPaneFactory.createScrollPane(body, true).also {
    UIUtil.uiTraverser(body).filter(JComponent::class.java).forEach { control ->
        control.whenFocusGained(owner) {
            // Scroll the tool panel even when the focused control handles scrolling internally.
            val target = focusTargets.getOrDefault(control, control)
            body.scrollRectToVisible(SwingUtilities.convertRectangle(target.parent, target.bounds, body))
        }
    }
}

internal fun AbstractTableModel.publishRowRefresh(previousRowCount: Int) {
    // A whole-table change clears Swing selection even when the row indices are unchanged.
    if (previousRowCount > 0 && previousRowCount == rowCount) {
        fireTableRowsUpdated(0, rowCount - 1)
    } else {
        fireTableDataChanged()
    }
}

internal data class MarsToolRunContent(val id: Long, val name: String, val process: MarsProcessHandler) {
    val label get() = toolsMessages.getMessage("counter.execution", name, id.toString())
}

internal fun marsToolExecutions(project: Project): List<MarsToolRunContent> =
    RunContentManager.getInstance(project).allDescriptors.mapNotNull { descriptor ->
        (descriptor.processHandler as? MarsProcessHandler)?.let {
            MarsToolRunContent(descriptor.executionId, descriptor.displayName, it)
        }
    }.sortedByDescending(MarsToolRunContent::id)

internal class MarsToolExecutionSelector(private val project: Project, private val selectionChanged: () -> Unit) {
    private val selector = ComboBox<MarsToolRunContent>().apply {
        renderer = textListCellRenderer(toolsMessages.getMessage("counter.empty"), MarsToolRunContent::label)
        addActionListener { selectionChanged() }
    }
    val component: JComponent get() = selector
    val process: MarsProcessHandler? get() = (selector.selectedItem as MarsToolRunContent?)?.process
    private var executions = emptyList<MarsToolRunContent>()

    fun refresh() {
        val available = marsToolExecutions(project)
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
