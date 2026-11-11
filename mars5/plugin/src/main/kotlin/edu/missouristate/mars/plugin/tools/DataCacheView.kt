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
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.plugin.tools.DataCacheSimulator.Access
import edu.missouristate.mars.plugin.tools.DataCacheSimulator.Options
import edu.missouristate.mars.plugin.tools.DataCacheSimulator.Placement
import edu.missouristate.mars.plugin.tools.DataCacheSimulator.Replacement
import edu.missouristate.mars.util.Binary
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JProgressBar
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.table.AbstractTableModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class DataCacheView(project: Project) : Disposable {
    private val selector = MarsToolExecutionSelector(project, ::selectionChanged)
    private val placement = ComboBox(Placement.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { toolsMessages.getMessage(it.messageKey) }
    }
    private val blocks = ComboBox(DataCacheSimulator.sizes.toTypedArray())
    private val words = ComboBox(DataCacheSimulator.sizes.toTypedArray())
    private val ways = ComboBox<Int>()
    private val replacement = ComboBox(Replacement.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { toolsMessages.getMessage(it.messageKey) }
    }
    private val apply = JButton(toolsMessages.getMessage("cache.apply"))
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val logging = JBCheckBox(toolsMessages.getMessage("cache.logging"))
    private val summary = JBLabel()
    private val traffic = JBLabel()
    private val percentage = JBLabel()
    private val rate = JProgressBar(0, 100).apply {
        accessibleContext.accessibleName = toolsMessages.getMessage("cache.rate")
    }
    private val blockModel = CacheBlockTableModel()
    private val table = JBTable(blockModel).apply {
        preferredScrollableViewportSize = JBDimension(300, 160)
        fitToolColumns("", "", Binary.intToHexString(0), "", toolsMessages.getMessage("cache.empty"))
    }
    private val log = JBTextArea(5, 35).apply { isEditable = false }
    private val tableScroll = JBScrollPane(table).apply { minimumSize = JBDimension(0, 84) }
    private val logScroll = JBScrollPane(log)
    private var previous: DataCacheSimulator.Snapshot? = null

    val component = scrollableToolPanel(this, marsToolPanel {
        marsExecutionRow(selector.component)
        row { cell(placement).label(toolsMessages.getMessage("cache.placement"), LabelPosition.TOP) }
        row(toolsMessages.getMessage("cache.blocks")) { cell(blocks) }
        row(toolsMessages.getMessage("cache.words")) { cell(words) }
        row(toolsMessages.getMessage("cache.ways")) { cell(ways) }
        row { cell(replacement).label(toolsMessages.getMessage("cache.replacement"), LabelPosition.TOP) }
        row { cell(apply) }
        row {
            cell(toggle)
            cell(reset)
        }
        row { cell(summary) }.topGap(TopGap.SMALL)
        row { cell(traffic) }
        row {
            label(toolsMessages.getMessage("cache.rate")).gap(RightGap.SMALL).component.labelFor = rate
            cell(percentage).gap(RightGap.SMALL)
            cell(rate).resizableColumn()
        }.layout(RowLayout.INDEPENDENT)
        row {
            cell(tableScroll).align(Align.FILL).resizableColumn()
        }.resizableRow()
        collapsibleGroup(toolsMessages.getMessage("cache.log"), indent = false) {
            row { cell(logging) }
            row { cell(logScroll).align(AlignX.FILL).resizableColumn() }
        }.expanded = false
        row { comment(toolsMessages.getMessage("cache.scope"), maxLineLength = 30) }
    }, mapOf(table to tableScroll, log to logScroll)).apply {
        horizontalScrollBarPolicy = HORIZONTAL_SCROLLBAR_NEVER
    }

    init {
        placement.addActionListener { updateWays() }
        blocks.addActionListener { updateWays() }
        replacement.addActionListener {
            selector.process?.dataCache?.setReplacement(replacement.selectedItem as Replacement)
            render()
        }
        apply.addActionListener {
            table.clearSelection()
            selector.process!!.dataCache.configure(
                Options(blocks.selectedItem as Int, words.selectedItem as Int, placement.selectedItem as Placement,
                    ways.selectedItem as Int, replacement.selectedItem as Replacement),
            )
            render()
        }
        toggle.addActionListener {
            val model = selector.process!!.dataCache
            model.setConnected(!model.snapshot().connected)
            render()
        }
        reset.addActionListener {
            table.clearSelection()
            selector.process!!.dataCache.reset()
            render()
        }
        logging.addActionListener {
            selector.process!!.dataCache.setLogging(logging.isSelected)
            render()
        }
        refresh()
        selectionChanged()
    }

    private val refreshJob = component.launchOnShow("Refresh MARS data cache") {
        while (true) {
            withContext(Dispatchers.EDT) { refresh() }
            delay(200)
        }
    }

    private fun refresh() {
        selector.refresh()
        render()
    }

    private fun updateWays() {
        val choices = (placement.selectedItem as Placement).wayChoices(blocks.selectedItem as Int)
        val selected = ways.selectedItem
        ways.model = DefaultComboBoxModel(choices.toTypedArray()).apply {
            selectedItem = choices.firstOrNull { it == selected } ?: choices.first()
        }
    }

    private fun selectionChanged() {
        table.clearSelection()
        val snapshot = selector.process?.dataCache?.snapshot()
        val options = snapshot?.options ?: Options()
        placement.selectedItem = options.placement
        blocks.selectedItem = options.blocks
        words.selectedItem = options.wordsPerBlock
        replacement.selectedItem = options.replacement
        updateWays()
        ways.selectedItem = options.ways
        logging.isSelected = snapshot?.logging == true
        render()
    }

    private fun render() {
        val snapshot = selector.process?.dataCache?.snapshot()
        for (control in listOf(placement, blocks, words, ways, replacement, apply, toggle, reset, logging)) {
            control.isEnabled = snapshot != null
        }
        toggle.text = toolsMessages.getMessage(if (snapshot?.connected == true) "counter.disconnect" else "counter.connect")
        if (snapshot === previous) return
        previous = snapshot
        summary.text = snapshot?.let {
            toolsMessages.getMessage("cache.summary", it.options.bytes.toString(), it.accesses.toString())
        }.orEmpty()
        traffic.text = snapshot?.let {
            toolsMessages.getMessage("cache.traffic", it.hits.toString(), it.misses.toString())
        }.orEmpty()
        rate.value = snapshot?.hitRate ?: 0
        percentage.text = toolsMessages.getMessage("cache.percent", rate.value.toString())
        blockModel.update(snapshot)
        log.text = snapshot?.recent.orEmpty().joinToString("\n", transform = ::logLine)
        log.caretPosition = log.document.length
    }

    override fun dispose() {
        refreshJob.cancel()
    }

    private class CacheBlockTableModel : AbstractTableModel() {
        private val columns = listOf("block", "set", "tag", "access", "result")
        private var snapshot: DataCacheSimulator.Snapshot? = null

        fun update(snapshot: DataCacheSimulator.Snapshot?) {
            val previousRowCount = rowCount
            this.snapshot = snapshot
            publishRowRefresh(previousRowCount)
        }

        override fun getColumnName(column: Int) = toolsMessages.getMessage("cache.column.${columns[column]}")
        override fun getColumnCount() = columns.size
        override fun getRowCount() = snapshot?.let { it.blocks.size } ?: 0
        override fun getValueAt(rowIndex: Int, columnIndex: Int): String {
            val state = snapshot!!
            val block = state.blocks[rowIndex]
            return when (columnIndex) {
                0 -> rowIndex.toString()
                1 -> (rowIndex / state.options.ways).toString()
                2 -> block.tag?.let(Binary::intToHexString).orEmpty()
                3 -> block.lastAccess.takeIf { it != 0L }?.toString().orEmpty()
                else -> toolsMessages.getMessage(when {
                    block.tag == null -> "cache.empty"
                    block.lastHit -> "cache.hit"
                    else -> "cache.miss"
                })
            }
        }
    }
}

private fun logLine(access: Access): String {
    val result = when {
        access.hit -> toolsMessages.getMessage("cache.hit")
        access.previousTag == null -> toolsMessages.getMessage("cache.fill")
        else -> toolsMessages.getMessage("cache.evict", Binary.intToHexString(access.previousTag))
    }
    return toolsMessages.getMessage(
        "cache.observation", access.number.toString(), toolsMessages.getMessage(if (access.write) "cache.write" else "cache.read"),
        Binary.intToHexString(access.address), Binary.intToHexString(access.tag), access.set.toString(), access.block.toString(), result,
    )
}
