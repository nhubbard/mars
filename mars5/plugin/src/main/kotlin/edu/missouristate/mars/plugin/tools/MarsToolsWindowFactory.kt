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
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JProgressBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class MarsToolsWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        fun addTab(name: String, view: InstructionCountsView) {
            val content = ContentFactory.getInstance().createContent(view.component, toolsMessages.getMessage(name), false)
            content.setDisposer(view)
            toolWindow.contentManager.addContent(content)
        }
        addTab("counter.name", InstructionCountsView(
            project, listOf("counter.r", "counter.i", "counter.j", "counter.other"), showBars = false,
            MarsProcessHandler::instructionCounter,
        ))
        addTab("statistics.name", InstructionCountsView(
            project, InstructionStatistics.Category.entries.map { it.messageKey }, showBars = true,
            MarsProcessHandler::instructionStatistics,
        ))
    }
}

private val toolsMessages = DynamicBundle(MarsToolsWindowFactory::class.java, "messages.MarsBundle")

private class InstructionCountsView(
    private val project: Project, categories: List<String>, showBars: Boolean,
    private val counts: (MarsProcessHandler) -> InstructionCounts,
) : Disposable {
    private data class Execution(val id: Long, val name: String, val process: MarsProcessHandler)

    private val selector = ComboBox<Execution>().apply {
        renderer = textListCellRenderer(toolsMessages.getMessage("counter.empty")) {
            toolsMessages.getMessage("counter.execution", it.name, it.id.toString())
        }
    }
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val total = JBLabel()
    private val labels = categories.map { JBLabel() }
    private val bars = if (showBars) categories.map { key ->
        JProgressBar(0, 100).apply { accessibleContext.accessibleName = toolsMessages.getMessage(key) }
    } else emptyList()
    private val emptySnapshot = InstructionCountsSnapshot(false, List(categories.size) { 0L })
    private var executions = emptyList<Execution>()

    val component = panel {
        row(toolsMessages.getMessage("counter.selector")) {
            cell(selector).align(AlignX.FILL).resizableColumn()
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
        selector.addActionListener { render() }
        toggle.addActionListener {
            val counter = counts((selector.selectedItem as Execution).process)
            counter.setConnected(!counter.countsSnapshot().connected)
            render()
        }
        reset.addActionListener {
            counts((selector.selectedItem as Execution).process).reset()
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
        val available = RunContentManager.getInstance(project).allDescriptors.mapNotNull { descriptor ->
            (descriptor.processHandler as? MarsProcessHandler)?.let {
                Execution(descriptor.executionId, descriptor.displayName, it)
            }
        }.sortedByDescending(Execution::id)
        if (available != executions) {
            val selected = selector.selectedItem
            executions = available
            selector.model = DefaultComboBoxModel(available.toTypedArray())
            selector.selectedItem = available.firstOrNull { it == selected } ?: available.firstOrNull()
        }
        render()
    }

    private fun render() {
        val execution = selector.selectedItem as Execution?
        val snapshot = execution?.let { counts(it.process).countsSnapshot() } ?: emptySnapshot
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
