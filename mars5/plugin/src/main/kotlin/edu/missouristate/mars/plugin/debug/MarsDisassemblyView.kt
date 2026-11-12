package edu.missouristate.mars.plugin.debug

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.ui.components.JBLabel
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.BaseTableView
import com.intellij.ui.table.TableView
import com.intellij.util.messages.Topic
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.simulator.BackStepper.ExecutedInstruction
import edu.missouristate.mars.util.Binary
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.RowSorter.SortKey
import javax.swing.SortOrder
import javax.swing.event.RowSorterEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val DISASSEMBLY_LAYOUT_PREFIX = "mars.debug.disassembly.v1."
// BaseTableView stores complete layouts. Keep the imported order separate until the next native layout save.
private const val importedOrderKey = DISASSEMBLY_LAYOUT_PREFIX + "importedOrder"
private val addressColumn = MarsDisassemblyColumn.ADDRESS.ordinal
private val sourceColumn = MarsDisassemblyColumn.SOURCE.ordinal

internal enum class MarsDisassemblyColumn(private val suffix: String) {
    PC("pc"), ADDRESS("address"), WORD("word"), INSTRUCTION("instruction"), SOURCE("source"),
    BREAKPOINT("breakpoint"), EXECUTION("execution");

    val title get() = runMessages.getMessage("debug.disassembly.$suffix")
}

// Venus persisted the model indices of Bkpt, Address, Code, Basic, and Source in that order.
private val venusColumns = listOf(
    MarsDisassemblyColumn.BREAKPOINT, MarsDisassemblyColumn.ADDRESS, MarsDisassemblyColumn.WORD,
    MarsDisassemblyColumn.INSTRUCTION, MarsDisassemblyColumn.SOURCE,
)
private val columnSeparators = Regex("\\s+")

internal fun venusDisassemblyOrder(value: String?): List<MarsDisassemblyColumn>? {
    val indices = value?.trim()?.split(columnSeparators)?.map { it.toIntOrNull() ?: return null } ?: return null
    if (indices.size != venusColumns.size || indices.toSet() != venusColumns.indices.toSet()) return null
    return indices.map(venusColumns::get)
}

internal fun PropertiesComponent.importedDisassemblyOrder() = venusDisassemblyOrder(getValue(importedOrderKey))

internal fun importDisassemblyOrder(order: List<MarsDisassemblyColumn>) {
    PropertiesComponent.getInstance().setValue(importedOrderKey, order.joinToString(" ") { venusColumns.indexOf(it).toString() })
    ApplicationManager.getApplication().messageBus.syncPublisher(MarsDisassemblyOrderListener.TOPIC).orderImported(order)
}

internal fun interface MarsDisassemblyOrderListener {
    fun orderImported(order: List<MarsDisassemblyColumn>)

    companion object {
        @Topic.AppLevel
        @JvmField
        val TOPIC = Topic("MARS imported disassembly order", MarsDisassemblyOrderListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

internal data class ExecutionMarkers(val nextIsDelaySlot: Boolean = false, val previous: ExecutedInstruction? = null) {
    companion object {
        fun capture(machine: MarsContext) = ExecutionMarkers(machine.delayedBranch.isTriggered, machine.backStepper.previousExecution())
    }
}

internal data class DisassemblyRow(
    val current: Boolean,
    val address: Int,
    val word: String,
    val instruction: String,
    val location: Location?,
    val nextIsDelaySlot: Boolean,
    val previous: ExecutedInstruction?,
)

internal fun disassemblyWindow(
    memory: MemoryState, pc: Long, center: Long = pc, markers: ExecutionMarkers = ExecutionMarkers(),
): List<DisassemblyRow> {
    if (!MemoryState.wordAligned(center.toInt())) return emptyList()
    val start = (center - 4 * Int.SIZE_BYTES).coerceAtLeast(0)
    val end = (center + 11 * Int.SIZE_BYTES).coerceAtMost(0xffff_fffcL)
    val count = ((end - start) / Int.SIZE_BYTES + 1).toInt()
    return List(count) { index ->
        val address = start + index * Int.SIZE_BYTES
        val statement = memory.inspectInstruction(address.toInt())
        DisassemblyRow(
            address == pc,
            address.toInt(),
            statement?.let { Binary.intToHexString(it.binaryStatement) } ?: "—",
            statement?.printableBasicAssemblyStatement?.trimEnd() ?: "—",
            statement?.instructionOrigin?.location(),
            address == pc && markers.nextIsDelaySlot,
            markers.previous?.takeIf { it.address == address.toInt() },
        )
    }
}

internal class MarsDisassemblyView(
    private val session: XDebugSession,
    private val entry: String,
    private val sourcePosition: (Location) -> XSourcePosition,
) : Disposable {
    private val display = service<MarsDebuggerDisplaySettings>()
    private val breakpointManager = XDebuggerManager.getInstance(session.project).breakpointManager
    private val breakpointType = XDebuggerUtil.getInstance().findBreakpointType(MarsAddressBreakpointType::class.java)
    private val status = JBLabel()
    private val followPc = JButton(runMessages.getMessage("debug.disassembly.follow.pc"))
    private val lastExecuted = JButton(runMessages.getMessage("debug.disassembly.last.executed"))
    private val openSource = JButton(runMessages.getMessage("debug.disassembly.open.source")).apply { isEnabled = false }
    private val toggleBreakpoint = JButton(runMessages.getMessage("debug.disassembly.breakpoint.toggle")).apply { isEnabled = false }
    private var breakpoints = emptyMap<Int, AddressBreakpoint>()
    private val model = ListTableModel<DisassemblyRow>(
        column(MarsDisassemblyColumn.PC) { if (it.current) "▶" else "" },
        sortableColumn(MarsDisassemblyColumn.ADDRESS, { display.formatAddress(it.address) }, compareBy { it.address.toUInt() }),
        sortableColumn(MarsDisassemblyColumn.WORD, DisassemblyRow::word),
        sortableColumn(MarsDisassemblyColumn.INSTRUCTION, DisassemblyRow::instruction),
        sortableColumn(MarsDisassemblyColumn.SOURCE, ::sourceLabel),
        object : ColumnInfo<DisassemblyRow, Icon>(MarsDisassemblyColumn.BREAKPOINT.title) {
            override fun getColumnClass() = Icon::class.java
            override fun valueOf(item: DisassemblyRow): Icon? = breakpointIcon(item.address)
        },
        column(MarsDisassemblyColumn.EXECUTION) { row ->
            buildList {
                if (row.nextIsDelaySlot) add(runMessages.getMessage("debug.disassembly.delay.next"))
                row.previous?.let {
                    val key = if (it.delaySlot) "debug.disassembly.previous.delay" else "debug.disassembly.previous"
                    add(runMessages.getMessage(key))
                }
            }.joinToString(" · ")
        },
    )
    private val table = TableView(model).apply {
        font = Font(Font.MONOSPACED, font.style, font.size)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        val samples = listOf(
            "▶", "0xffffffff", "0x00000000", "addiu $00, $00, 0x00000000", "program-name.s:1234", "",
            runMessages.getMessage("debug.disassembly.previous.delay"),
        )
        val metrics = getFontMetrics(font)
        val renderer = executionRenderer()
        for ((index, sample) in samples.withIndex()) {
            val column = columnModel.getColumn(index)
            if (index != MarsDisassemblyColumn.BREAKPOINT.ordinal) column.cellRenderer = renderer
            val width = maxOf(metrics.stringWidth(sample), metrics.stringWidth(column.headerValue.toString()))
            column.preferredWidth = width + JBUI.scale(16)
        }
        columnModel.getColumn(0).maxWidth = columnModel.getColumn(0).preferredWidth
    }
    private var target: Location? = null
    private var browsedAddress: Pair<Long, Long>? = null
    private var previousAddress: Int? = null
    private var displayedAddresses: Pair<Long, Long?>? = null
    private val revision = AtomicLong()
    @Volatile private var disposed = false

    val focus get() = table
    val component = panel {
        row {
            cell(status)
            cell(followPc)
            cell(lastExecuted)
            cell(openSource)
            cell(toggleBreakpoint)
        }
        row { scrollCell(table).align(Align.FILL) }.resizableRow()
    }

    init {
        onMachineHighlightSchemeChange(this, table::repaint)
        restoreTableLayout()
        display.addListener(this) {
            updateStatus()
            table.repaint()
        }
        table.selectionModel.addListSelectionListener { updateSelection() }
        followPc.addActionListener {
            browsedAddress = null
            refresh()
        }
        lastExecuted.addActionListener { showAddress(previousAddress!!) }
        openSource.addActionListener { sourcePosition(target!!).createNavigatable(session.project).navigate(true) }
        toggleBreakpoint.addActionListener { toggleSelectedBreakpoint() }
        breakpointManager.addBreakpointListener(breakpointType, object : XBreakpointListener<AddressBreakpoint> {
            override fun breakpointAdded(breakpoint: AddressBreakpoint) = onEdt(::reloadBreakpoints)
            override fun breakpointRemoved(breakpoint: AddressBreakpoint) = onEdt(::reloadBreakpoints)
            override fun breakpointChanged(breakpoint: AddressBreakpoint) = onEdt(::reloadBreakpoints)
        }, this)
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = scheduleRefresh()
            override fun settingsChanged() = scheduleRefresh()
            override fun sessionResumed() = scheduleRefresh()
            override fun sessionStopped() = scheduleRefresh()
            override fun breakpointsMuted(muted: Boolean) = onEdt(table::repaint)
        }, this)
        reloadBreakpoints()
        refresh()
    }

    private fun restoreTableLayout() {
        val properties = PropertiesComponent.getInstance()
        BaseTableView.restore(properties, DISASSEMBLY_LAYOUT_PREFIX, table)
        // The platform's persisted layout owns column widths. Move the existing columns after it restores them.
        properties.importedDisassemblyOrder()?.let(::applyImportedOrder)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(MarsDisassemblyOrderListener.TOPIC, MarsDisassemblyOrderListener(::applyImportedOrder))
        val sorter = table.rowSorter
        val column = properties.getInt(DISASSEMBLY_LAYOUT_PREFIX + "sortColumn", addressColumn)
            .coerceIn(addressColumn, sourceColumn)
        val order = if (properties.getBoolean(DISASSEMBLY_LAYOUT_PREFIX + "descending")) SortOrder.DESCENDING else SortOrder.ASCENDING
        sorter.sortKeys = listOf(SortKey(column, order))
        sorter.addRowSorterListener { event ->
            if (event.type != RowSorterEvent.Type.SORT_ORDER_CHANGED) return@addRowSorterListener
            val key = sorter.sortKeys.firstOrNull() ?: SortKey(addressColumn, SortOrder.ASCENDING)
            properties.setValue(DISASSEMBLY_LAYOUT_PREFIX + "sortColumn", key.column, addressColumn)
            properties.setValue(DISASSEMBLY_LAYOUT_PREFIX + "descending", key.sortOrder == SortOrder.DESCENDING)
        }
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            private fun layout() = table.columnModel.columns.asSequence().map { it.modelIndex to it.width }.toList()
            private var before = layout()

            override fun mousePressed(event: MouseEvent) {
                before = layout()
            }

            override fun mouseReleased(event: MouseEvent) {
                if (before != layout()) {
                    BaseTableView.store(properties, DISASSEMBLY_LAYOUT_PREFIX, table)
                    properties.unsetValue(importedOrderKey)
                }
            }
        })
    }

    private fun applyImportedOrder(order: List<MarsDisassemblyColumn>) {
        val imported = order.iterator()
        val columns = table.columnModel
        val target = columns.columns.asSequence().map { column ->
            val current = MarsDisassemblyColumn.entries[column.modelIndex]
            if (current in venusColumns) imported.next() else current
        }.toList()
        for ((index, column) in target.withIndex()) {
            val previous = table.convertColumnIndexToView(column.ordinal)
            if (index != previous) columns.moveColumn(previous, index)
        }
    }

    private fun executionRenderer() = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val text = value as String
            val instruction = model.getItem(table.convertRowIndexToModel(row))
            if (!instruction.current) {
                append(text)
                return
            }
            val highlight = if (instruction.nextIsDelaySlot) MarsMachineHighlight.DELAY else MarsMachineHighlight.CURRENT
            val style = highlight.tableStyle()
            if (!selected) style.bgColor?.let { background = it }
            append(text, style)
        }
    }

    fun showAddress(address: Int) {
        val context = session.suspendContext as? MarsSuspendContext ?: return
        browsedAddress = context.number to address.toUInt().toLong()
        refresh()
    }

    private fun scheduleRefresh() {
        // A session event invalidates an in-flight read before its Swing refresh runs.
        revision.incrementAndGet()
        onEdt(::refresh)
    }

    private fun onEdt(task: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(task, ModalityState.defaultModalityState()) { disposed }
    }

    private fun refresh() {
        val request = revision.incrementAndGet()
        table.clearSelection()
        model.items = emptyList()
        val context = session.suspendContext as? MarsSuspendContext
        previousAddress = context?.executionMarkers?.previous?.address
        lastExecuted.isEnabled = previousAddress != null
        if (context == null) {
            followPc.isEnabled = false
            displayedAddresses = null
            updateStatus()
            return
        }
        val pc = context.memoryRegions.getValue(MemoryRegion.PC)
        val browsing = browsedAddress
        val browsed = if (browsing?.first == context.number) browsing.second else null
        followPc.isEnabled = browsed != null
        val center = browsed ?: pc
        displayedAddresses = pc to browsed
        updateStatus()
        val markers = context.executionMarkers
        context.inspectionRequests.trySend { memory ->
            val rows = disassemblyWindow(memory, pc, center, markers)
            withContext(Dispatchers.EDT) {
                if (request == revision.get()) {
                    model.items = rows
                    val selected = rows.firstOrNull { it.address == center.toInt() }
                    if (selected != null) {
                        table.setSelection(listOf(selected))
                        val visible = table.visibleRect
                        val row = table.getCellRect(table.selectedRow, 0, true)
                        row.x = visible.x
                        row.width = visible.width
                        table.scrollRectToVisible(row)
                    }
                }
            }
        }
    }

    private fun updateStatus() {
        val addresses = displayedAddresses
        if (addresses == null) {
            status.text = runMessages.getMessage("debug.disassembly.pause")
            return
        }
        val (pc, browsed) = addresses
        val pcText = display.formatAddress(pc.toInt())
        status.text = if (browsed == null) runMessages.getMessage("debug.disassembly.current", pcText)
        else runMessages.getMessage("debug.disassembly.browsing", display.formatAddress(browsed.toInt()), pcText)
    }

    private fun updateSelection() {
        val selected = table.selectedObject
        toggleBreakpoint.isEnabled = selected != null
        target = selected?.location
        openSource.isEnabled = target != null
    }

    private fun reloadBreakpoints() {
        breakpoints = breakpointManager.getBreakpoints(breakpointType).mapNotNull { breakpoint ->
            breakpoint.properties?.addressIn(entry)?.let { it to breakpoint }
        }.toMap()
        table.repaint()
    }

    private fun breakpointIcon(address: Int): Icon? {
        val breakpoint = breakpoints[address] ?: return null
        return when {
            !breakpoint.isEnabled -> breakpointType.disabledIcon
            session.areBreakpointsMuted() -> breakpointType.mutedEnabledIcon
            breakpoint.suspendPolicy == SuspendPolicy.NONE -> breakpointType.suspendNoneIcon
            else -> breakpointType.enabledIcon
        }
    }

    private fun toggleSelectedBreakpoint() {
        val address = table.selectedObject!!.address
        WriteAction.run<RuntimeException> {
            val existing = breakpointManager.getBreakpoints(breakpointType).firstOrNull { it.properties?.addressIn(entry) == address }
            if (existing == null) breakpointManager.addBreakpoint(breakpointType, MarsAddressBreakpointProperties(entry, address))
            else breakpointManager.removeBreakpoint(existing)
        }
    }

    override fun dispose() {
        disposed = true
        revision.incrementAndGet()
    }

    private fun column(column: MarsDisassemblyColumn, value: (DisassemblyRow) -> String) =
        object : ColumnInfo<DisassemblyRow, String>(column.title) {
            override fun valueOf(item: DisassemblyRow) = value(item)
        }

    private fun sortableColumn(
        column: MarsDisassemblyColumn, value: (DisassemblyRow) -> String, order: Comparator<DisassemblyRow> = compareBy(value),
    ) = object : ColumnInfo<DisassemblyRow, String>(column.title) {
        override fun valueOf(item: DisassemblyRow) = value(item)
        override fun getComparator() = order
    }

    private fun sourceLabel(row: DisassemblyRow) = row.location?.let { "${it.file().fileName}:${it.line()}" }.orEmpty()
}
