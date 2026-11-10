package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary
import java.awt.Font
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.ListSelectionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DisassemblyRow(
    val current: Boolean,
    val address: Int,
    val word: String,
    val instruction: String,
    val location: Location?,
)

internal fun disassemblyWindow(memory: MemoryState, pc: Long): List<DisassemblyRow> {
    if (!MemoryState.wordAligned(pc.toInt())) return emptyList()
    val start = (pc - 4 * Int.SIZE_BYTES).coerceAtLeast(0)
    val end = (pc + 11 * Int.SIZE_BYTES).coerceAtMost(0xffff_fffcL)
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
        )
    }
}

internal class MarsDisassemblyView(
    private val session: XDebugSession,
    private val entry: String,
    private val sourcePosition: (Location) -> XSourcePosition,
) : Disposable {
    private val breakpointManager = XDebuggerManager.getInstance(session.project).breakpointManager
    private val breakpointType = XDebuggerUtil.getInstance().findBreakpointType(MarsAddressBreakpointType::class.java)
    private val status = JBLabel()
    private val openSource = JButton(runMessages.getMessage("debug.disassembly.open.source")).apply { isEnabled = false }
    private val toggleBreakpoint = JButton(runMessages.getMessage("debug.disassembly.breakpoint.toggle")).apply { isEnabled = false }
    private var breakpoints = emptyMap<Int, AddressBreakpoint>()
    private val model = ListTableModel<DisassemblyRow>(
        column("debug.disassembly.pc") { if (it.current) "▶" else "" },
        column("debug.disassembly.address") { Binary.intToHexString(it.address) },
        column("debug.disassembly.word", DisassemblyRow::word),
        column("debug.disassembly.instruction", DisassemblyRow::instruction),
        column("debug.disassembly.source") { it.location?.let { site -> "${site.file().fileName}:${site.line()}" }.orEmpty() },
        object : ColumnInfo<DisassemblyRow, Icon>(runMessages.getMessage("debug.disassembly.breakpoint")) {
            override fun getColumnClass() = Icon::class.java
            override fun valueOf(item: DisassemblyRow): Icon? = breakpointIcon(item.address)
        },
    )
    private val table = JBTable(model).apply {
        font = Font(Font.MONOSPACED, font.style, font.size)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        columnModel.getColumn(0).maxWidth = getFontMetrics(font).stringWidth(" PC ")
        columnModel.getColumn(3).preferredWidth = getFontMetrics(font).stringWidth("addiu $00, $00, 0x00000000")
    }
    private var target: Location? = null
    private val revision = AtomicLong()
    @Volatile private var disposed = false

    val focus get() = table
    val component = panel {
        row {
            cell(status)
            cell(openSource)
            cell(toggleBreakpoint)
        }
        row { scrollCell(table).align(Align.FILL) }.resizableRow()
    }

    init {
        table.selectionModel.addListSelectionListener { updateSelection() }
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
        if (context == null) {
            status.text = runMessages.getMessage("debug.disassembly.pause")
            return
        }
        val pc = context.memoryRegions.getValue(MemoryRegion.PC)
        status.text = runMessages.getMessage("debug.disassembly.current", Binary.intToHexString(pc.toInt()))
        context.inspectionRequests.trySend { memory ->
            val rows = disassemblyWindow(memory, pc)
            withContext(Dispatchers.EDT) {
                if (request == revision.get()) {
                    model.items = rows
                    val current = rows.indexOfFirst(DisassemblyRow::current)
                    if (current >= 0) {
                        table.setRowSelectionInterval(current, current)
                        table.scrollRectToVisible(table.getCellRect(current, 0, true))
                    }
                }
            }
        }
    }

    private fun updateSelection() {
        val selected = model.items.getOrNull(table.selectedRow)
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
        val address = model.items[table.selectedRow].address
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

    private fun column(key: String, value: (DisassemblyRow) -> String) =
        object : ColumnInfo<DisassemblyRow, String>(runMessages.getMessage(key)) {
            override fun valueOf(item: DisassemblyRow) = value(item)
        }
}
