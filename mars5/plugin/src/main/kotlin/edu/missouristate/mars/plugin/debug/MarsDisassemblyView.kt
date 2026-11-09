package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary
import java.awt.Font
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.ListSelectionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DisassemblyRow(
    val current: Boolean,
    val address: String,
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
            Binary.intToHexString(address.toInt()),
            statement?.let { Binary.intToHexString(it.binaryStatement) } ?: "—",
            statement?.printableBasicAssemblyStatement?.trimEnd() ?: "—",
            statement?.instructionOrigin?.location(),
        )
    }
}

internal class MarsDisassemblyView(private val session: XDebugSession) : Disposable {
    private val status = JBLabel()
    private val openSource = JButton(runMessages.getMessage("debug.disassembly.open.source")).apply { isEnabled = false }
    private val model = ListTableModel<DisassemblyRow>(
        column("debug.disassembly.pc") { if (it.current) "▶" else "" },
        column("debug.disassembly.address", DisassemblyRow::address),
        column("debug.disassembly.word", DisassemblyRow::word),
        column("debug.disassembly.instruction", DisassemblyRow::instruction),
        column("debug.disassembly.source") { it.location?.let { site -> "${site.file().fileName}:${site.line()}" }.orEmpty() },
    )
    private val table = JBTable(model).apply {
        font = Font(Font.MONOSPACED, font.style, font.size)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        columnModel.getColumn(0).maxWidth = getFontMetrics(font).stringWidth(" PC ")
        columnModel.getColumn(3).preferredWidth = getFontMetrics(font).stringWidth("addiu $00, $00, 0x00000000")
    }
    private var target: OpenFileDescriptor? = null
    private val revision = AtomicLong()
    @Volatile private var disposed = false

    val focus get() = table
    val component = panel {
        row {
            cell(status)
            cell(openSource)
        }
        row { scrollCell(table).align(Align.FILL) }.resizableRow()
    }

    init {
        table.selectionModel.addListSelectionListener { updateNavigation() }
        openSource.addActionListener { target!!.navigate(true) }
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = scheduleRefresh()
            override fun sessionResumed() = scheduleRefresh()
            override fun sessionStopped() = scheduleRefresh()
        }, this)
        refresh()
    }

    private fun scheduleRefresh() {
        // A session event invalidates an in-flight read before its Swing refresh runs.
        revision.incrementAndGet()
        ApplicationManager.getApplication().invokeLater(::refresh, ModalityState.defaultModalityState()) { disposed }
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

    private fun updateNavigation() {
        target = model.items.getOrNull(table.selectedRow)?.location?.let { location ->
            LocalFileSystem.getInstance().findFileByNioFile(location.file())?.let { file ->
                OpenFileDescriptor(session.project, file, location.line() - 1, 0)
            }
        }
        openSource.isEnabled = target != null
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
