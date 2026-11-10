package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.tools.BranchHistoryTable.Observation
import edu.missouristate.mars.plugin.tools.BranchHistoryTable.Options
import edu.missouristate.mars.util.Binary
import javax.swing.JButton
import javax.swing.table.AbstractTableModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class BranchHistoryView(project: Project) : Disposable {
    private val selector = MarsToolExecutionSelector(project, ::selectionChanged)
    private val size = ComboBox(BranchHistoryTable.sizes.toTypedArray())
    private val history = ComboBox(BranchHistoryTable.histories.toTypedArray())
    private val initialTaken = JBCheckBox(toolsMessages.getMessage("branchHistory.initialTaken"))
    private val apply = JButton(toolsMessages.getMessage("branchHistory.apply"))
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val tableModel = HistoryTableModel()
    private val table = JBTable(tableModel)
    private val latest = JBLabel()
    private val log = JBTextArea(5, 32).apply { isEditable = false }
    private var previous: BranchHistoryTable.Snapshot? = null

    val component = panel {
        row(toolsMessages.getMessage("counter.selector")) { cell(selector.component).align(AlignX.FILL).resizableColumn() }
        row(toolsMessages.getMessage("branchHistory.size")) { cell(size) }
        row(toolsMessages.getMessage("branchHistory.bits")) { cell(history) }
        row {
            cell(initialTaken)
            cell(apply)
        }
        row {
            cell(toggle)
            cell(reset)
        }
        row(toolsMessages.getMessage("branchHistory.latest.label")) { cell(latest) }
        row { cell(JBScrollPane(table)).align(Align.FILL).resizableColumn() }.resizableRow()
        row { label(toolsMessages.getMessage("branchHistory.log")).component.labelFor = log }
        row { cell(JBScrollPane(log)).align(AlignX.FILL).resizableColumn() }
        row { comment(toolsMessages.getMessage("branchHistory.reverse")) }
    }

    init {
        apply.addActionListener {
            val model = selector.process!!.branchHistory
            model.configure(Options(size.selectedItem as Int, history.selectedItem as Int, initialTaken.isSelected))
            render()
        }
        toggle.addActionListener {
            val model = selector.process!!.branchHistory
            model.setConnected(!model.snapshot().connected)
            render()
        }
        reset.addActionListener {
            selector.process!!.branchHistory.reset()
            selectionChanged()
        }
        refresh()
        selectionChanged()
    }

    private val refreshJob = component.launchOnShow("Refresh MARS branch history") {
        while (true) {
            withContext(Dispatchers.EDT) { refresh() }
            delay(200)
        }
    }

    private fun refresh() {
        selector.refresh()
        render()
    }

    private fun selectionChanged() {
        val options = selector.process?.branchHistory?.snapshot()?.options ?: Options()
        size.selectedItem = options.size
        history.selectedItem = options.historyBits
        initialTaken.isSelected = options.initialTaken
        render()
    }

    private fun render() {
        val snapshot = selector.process?.branchHistory?.snapshot()
        val available = snapshot != null
        for (control in listOf(size, history, initialTaken, apply, toggle, reset)) control.isEnabled = available
        toggle.text = toolsMessages.getMessage(if (snapshot?.connected == true) "counter.disconnect" else "counter.connect")
        if (snapshot == previous) return
        previous = snapshot
        val decision = snapshot?.recent?.lastOrNull()
        latest.text = decision?.let {
            val assembly = ProgramStatement(it.instruction, it.pc, InstructionDefaults.get()).printableBasicAssemblyStatement.trimEnd()
            toolsMessages.getMessage("branchHistory.latest", Binary.intToHexString(it.pc), assembly, it.index.toString())
        } ?: toolsMessages.getMessage("branchHistory.latest.empty")
        tableModel.update(snapshot?.rows.orEmpty(), decision)
        log.text = snapshot?.recent.orEmpty().joinToString("\n") {
            toolsMessages.getMessage(
                "branchHistory.observation", Binary.intToHexString(it.pc), Binary.intToHexString(it.target),
                it.index.toString(), prediction(it.predictedTaken), prediction(it.taken), result(it),
            )
        }
        log.caretPosition = log.document.length
    }

    override fun dispose() {
        refreshJob.cancel()
    }

    private class HistoryTableModel : AbstractTableModel() {
        private val columns = listOf("index", "history", "prediction", "correct", "incorrect", "precision", "latest")
        private var rows = emptyList<List<String>>()

        fun update(entries: List<BranchHistoryTable.Row>, decision: Observation?) {
            rows = entries.mapIndexed { index, row ->
                listOf(
                    index.toString(), row.history.joinToString(", ", transform = ::prediction),
                    prediction(row.predictedTaken), row.correct.toString(), row.incorrect.toString(),
                    toolsMessages.getMessage("branchHistory.percent", row.precision),
                    if (decision?.index == index) result(decision) else "",
                )
            }
            fireTableDataChanged()
        }

        override fun getColumnName(column: Int) = toolsMessages.getMessage("branchHistory.column.${columns[column]}")
        override fun getColumnCount() = columns.size
        override fun getRowCount() = rows.size
        override fun getValueAt(rowIndex: Int, columnIndex: Int) = rows[rowIndex][columnIndex]
    }
}

private fun prediction(taken: Boolean) =
    toolsMessages.getMessage(if (taken) "branchHistory.taken" else "branchHistory.notTaken")

private fun result(decision: Observation) =
    toolsMessages.getMessage(if (decision.correct) "branchHistory.result.correct" else "branchHistory.result.incorrect")
