package edu.missouristate.mars.plugin.run

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import edu.missouristate.mars.mips.dump.DumpFormatLoader
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.build.MemoryExportOptions
import edu.missouristate.mars.plugin.build.MemoryExportRegion
import edu.missouristate.mars.plugin.build.MemoryExportSelection
import edu.missouristate.mars.plugin.build.chooseMemoryExport
import edu.missouristate.mars.plugin.build.memoryExportMessages
import edu.missouristate.mars.plugin.projectFilePath
import edu.missouristate.mars.util.Binary
import java.nio.file.Path
import javax.swing.ListSelectionModel

internal class RunMemoryDumpState : BaseState() {
    var outputPath by string()
    var formatName by string()
    var region by enum(MemoryExportRegion.TEXT)
    var firstAddress by property(0L)
    var lastAddress by property(0L)

    fun resolve(project: Project): MemoryExportSelection? {
        val output = projectFilePath(project, outputPath) ?: return null
        val format = DumpFormatLoader.findByCommandDescriptor(formatName) ?: return null
        val range = if (region == MemoryExportRegion.RANGE) {
            if (firstAddress !in 0..UInt.MAX_VALUE.toLong() || lastAddress !in firstAddress..UInt.MAX_VALUE.toLong() ||
                !MemoryState.wordAligned(firstAddress.toInt()) || !MemoryState.wordAligned(lastAddress.toInt())) return null
            firstAddress.toUInt()..lastAddress.toUInt()
        } else UIntRange.EMPTY
        return MemoryExportSelection(MemoryExportOptions(format, region, range), output)
    }
}

internal class RunMemoryDumpEditor(private val project: Project, private val entry: () -> Path?) {
    private val rows = CollectionListModel<RunMemoryDumpState>()
    private val list = JBList(rows).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 3
        emptyText.text = runMessages.getMessage("run.export.empty")
        cellRenderer = textListCellRenderer("") { row ->
            val region = if (row.region == MemoryExportRegion.RANGE) {
                runMessages.getMessage(
                    "run.export.item.range", Binary.intToHexString(row.firstAddress.toInt()),
                    Binary.intToHexString(row.lastAddress.toInt()),
                )
            } else {
                memoryExportMessages.getMessage(row.region.label)
            }
            runMessages.getMessage("run.export.item", row.formatName.orEmpty(), region, row.outputPath.orEmpty())
        }
    }

    val component = ToolbarDecorator.createDecorator(list, rows)
        .setAddAction {
            val selected = chooseMemoryExport(project, entry()) ?: return@setAddAction
            rows.add(stateOf(selected))
            list.selectedIndex = rows.size - 1
        }
        .setEditAction {
            val index = list.selectedIndex
            if (index < 0) return@setEditAction
            val selected = chooseMemoryExport(project, entry(), rows.getElementAt(index).resolve(project)) ?: return@setEditAction
            rows.setElementAt(stateOf(selected), index)
        }
        .disableUpDownActions()
        .createPanel()

    fun reset(settings: List<RunMemoryDumpState>) = rows.replaceAll(settings)

    fun copyRows() = ArrayList(rows.items)

    private fun stateOf(selection: MemoryExportSelection) = RunMemoryDumpState().apply {
        outputPath = selection.output.toString()
        formatName = selection.options.format.commandDescriptor
        region = selection.options.region
        if (region == MemoryExportRegion.RANGE) {
            firstAddress = selection.options.range.first.toLong()
            lastAddress = selection.options.range.last.toLong()
        }
    }
}
