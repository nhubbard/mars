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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class MarsToolsWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = InstructionCounterView(project)
        val content = ContentFactory.getInstance().createContent(view.component, toolsMessages.getMessage("counter.name"), false)
        content.setDisposer(view)
        toolWindow.contentManager.addContent(content)
    }
}

private val toolsMessages = DynamicBundle(MarsToolsWindowFactory::class.java, "messages.MarsBundle")

private class InstructionCounterView(private val project: Project) : Disposable {
    private data class Execution(val id: Long, val name: String, val process: MarsProcessHandler)

    private val selector = ComboBox<Execution>().apply {
        renderer = textListCellRenderer(toolsMessages.getMessage("counter.empty")) {
            toolsMessages.getMessage("counter.execution", it.name, it.id.toString())
        }
    }
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val total = JBLabel()
    private val r = JBLabel()
    private val i = JBLabel()
    private val j = JBLabel()
    private val other = JBLabel()
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
        row(toolsMessages.getMessage("counter.r")) { cell(r) }
        row(toolsMessages.getMessage("counter.i")) { cell(i) }
        row(toolsMessages.getMessage("counter.j")) { cell(j) }
        row(toolsMessages.getMessage("counter.other")) { cell(other) }
        row { comment(toolsMessages.getMessage("counter.reverse.policy")) }
    }

    init {
        selector.addActionListener { render() }
        toggle.addActionListener {
            val counter = (selector.selectedItem as Execution).process.instructionCounter
            counter.setConnected(!counter.snapshot().connected)
            render()
        }
        reset.addActionListener {
            (selector.selectedItem as Execution).process.instructionCounter.reset()
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
        val snapshot = execution?.let { it.process.instructionCounter.snapshot() }
            ?: InstructionCounter.Snapshot(false, 0, 0, 0, 0)
        toggle.isEnabled = execution != null
        reset.isEnabled = execution != null
        toggle.text = toolsMessages.getMessage(if (snapshot.connected) "counter.disconnect" else "counter.connect")
        total.text = snapshot.total.toString()
        r.text = format(snapshot.r, snapshot)
        i.text = format(snapshot.i, snapshot)
        j.text = format(snapshot.j, snapshot)
        other.text = format(snapshot.other, snapshot)
    }

    private fun format(count: Long, snapshot: InstructionCounter.Snapshot) =
        toolsMessages.getMessage("counter.count", count.toString(), snapshot.percent(count))

    override fun dispose() {
        refreshJob.cancel()
    }
}
