package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
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
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.ListSelectionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            markers.previous?.takeIf { it.address() == address.toInt() },
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
        column("debug.disassembly.pc") { if (it.current) "▶" else "" },
        column("debug.disassembly.address") { display.formatAddress(it.address) },
        column("debug.disassembly.word", DisassemblyRow::word),
        column("debug.disassembly.instruction", DisassemblyRow::instruction),
        column("debug.disassembly.source") { it.location?.let { site -> "${site.file().fileName}:${site.line()}" }.orEmpty() },
        object : ColumnInfo<DisassemblyRow, Icon>(runMessages.getMessage("debug.disassembly.breakpoint")) {
            override fun getColumnClass() = Icon::class.java
            override fun valueOf(item: DisassemblyRow): Icon? = breakpointIcon(item.address)
        },
        column("debug.disassembly.execution") { row ->
            buildList {
                if (row.nextIsDelaySlot) add(runMessages.getMessage("debug.disassembly.delay.next"))
                row.previous?.let {
                    val key = if (it.delaySlot()) "debug.disassembly.previous.delay" else "debug.disassembly.previous"
                    add(runMessages.getMessage(key))
                }
            }.joinToString(" · ")
        },
    )
    private val table = JBTable(model).apply {
        font = Font(Font.MONOSPACED, font.style, font.size)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        columnModel.getColumn(0).maxWidth = getFontMetrics(font).stringWidth(" PC ")
        columnModel.getColumn(3).preferredWidth = getFontMetrics(font).stringWidth("addiu $00, $00, 0x00000000")
        columnModel.getColumn(6).preferredWidth =
            getFontMetrics(font).stringWidth(runMessages.getMessage("debug.disassembly.previous.delay")) + JBUI.scale(16)
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
        previousAddress = context?.executionMarkers?.previous?.address()
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
                    val selected = rows.indexOfFirst { it.address == center.toInt() }
                    if (selected >= 0) {
                        table.setRowSelectionInterval(selected, selected)
                        table.scrollRectToVisible(table.getCellRect(selected, 0, true))
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
