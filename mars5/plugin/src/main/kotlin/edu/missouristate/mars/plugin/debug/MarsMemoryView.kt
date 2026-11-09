package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary
import java.awt.Font
import java.util.HexFormat
import javax.swing.JButton
import javax.swing.JTable

private const val BYTES_PER_ROW = 16
private const val ROWS_PER_PAGE = 16
private const val BYTES_PER_PAGE = BYTES_PER_ROW * ROWS_PER_PAGE
private const val ADDRESS_SPACE = 0x1_0000_0000L
private const val LAST_ROW = ADDRESS_SPACE - BYTES_PER_ROW
private val hex = HexFormat.of()

internal data class MemoryRequest(val address: Long, val show: (List<MemoryRow>) -> Unit)

internal data class MemoryRow(val address: String, val bytes: String, val characters: String)

internal fun memoryPage(memory: MemoryState, address: Long): List<MemoryRow> {
    val rowCount = minOf(ROWS_PER_PAGE.toLong(), (ADDRESS_SPACE - address) / BYTES_PER_ROW).toInt()
    return List(rowCount) { row ->
        val start = (address + row * BYTES_PER_ROW).toInt()
        val words = List(BYTES_PER_ROW / Int.SIZE_BYTES) { memory.inspectWord(start + it * Int.SIZE_BYTES) }
        val bytes = List(BYTES_PER_ROW) { index ->
            val offset = index % Int.SIZE_BYTES
            val shift = Byte.SIZE_BITS * if (memory.byteOrder == MemoryState.LITTLE_ENDIAN) offset else Int.SIZE_BYTES - 1 - offset
            words[index / Int.SIZE_BYTES]?.let { it ushr shift and 0xff }
        }
        MemoryRow(
            Binary.intToHexString(start),
            bytes.joinToString(" ") { if (it == null) "--" else hex.toHexDigits(it.toByte()) },
            bytes.joinToString("") {
                val byte = it ?: 0
                if (byte in 0x20..0x7e) byte.toChar().toString() else "."
            },
        )
    }
}

/** The Debug tab requests bounded reads from the owner of the current suspension. */
internal class MarsMemoryView(private val session: XDebugSession) : Disposable {
    val addressField = JBTextField(12)
    private val go = JButton(runMessages.getMessage("debug.memory.go"))
    private val previous = JButton(runMessages.getMessage("debug.memory.previous"))
    private val next = JButton(runMessages.getMessage("debug.memory.next"))
    private val status = JBLabel()
    private val model = ListTableModel<MemoryRow>(
        column("debug.memory.address", MemoryRow::address),
        column("debug.memory.bytes", MemoryRow::bytes),
        column("debug.memory.ascii", MemoryRow::characters),
    )
    private val table = JBTable(model).apply {
        font = Font(Font.MONOSPACED, font.style, font.size)
        val metrics = getFontMetrics(font)
        rowHeight = metrics.height + JBUI.scale(4)
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        for ((index, sample) in listOf("0x00000000", "00 ".repeat(BYTES_PER_ROW), ".".repeat(BYTES_PER_ROW)).withIndex()) {
            columnModel.getColumn(index).preferredWidth = metrics.stringWidth(sample) + JBUI.scale(16)
        }
    }
    private var address = 0L
    private var displayedSuspension: Long? = null
    private var revision = 0L
    private var disposed = false

    val component = panel {
        row(runMessages.getMessage("debug.memory.address")) {
            cell(addressField)
            cell(go)
            cell(previous)
            cell(next)
        }
        row { scrollCell(table).align(Align.FILL) }.resizableRow()
        row { cell(status) }
    }

    init {
        go.addActionListener { navigate() }
        addressField.addActionListener { navigate() }
        previous.addActionListener { move(-BYTES_PER_PAGE) }
        next.addActionListener { move(BYTES_PER_PAGE) }
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = refreshOnEdt()
            override fun sessionResumed() = refreshOnEdt()
            override fun sessionStopped() = refreshOnEdt()
        }, this)
        refresh()
    }

    private fun refreshOnEdt() = UIUtil.invokeLaterIfNeeded { refresh() }

    private fun move(delta: Int) {
        address = (address + delta).coerceIn(0L, LAST_ROW)
        refresh()
    }

    private fun navigate() {
        val text = addressField.text.trim()
        val parsed = if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toUIntOrNull(16) else text.toUIntOrNull()
        if (parsed == null) {
            revision++
            status.text = runMessages.getMessage("debug.memory.invalid")
            return
        }
        address = parsed.toLong() / BYTES_PER_ROW * BYTES_PER_ROW
        refresh()
    }

    private fun refresh() {
        if (disposed) return
        val request = ++revision
        val context = session.suspendContext as? MarsSuspendContext
        val paused = context != null
        addressField.isEnabled = paused
        go.isEnabled = paused
        if (context == null) {
            previous.isEnabled = false
            next.isEnabled = false
            status.text = displayedSuspension?.let { runMessages.getMessage("debug.memory.last", it) }
                ?: runMessages.getMessage("debug.memory.pause")
            return
        }

        if (addressField.text.isEmpty()) address = context.dataAddress
        val start = address
        addressField.text = Binary.intToHexString(start.toInt())
        previous.isEnabled = start > 0
        next.isEnabled = start < LAST_ROW
        status.text = displayedSuspension?.let { runMessages.getMessage("debug.memory.loading.last", it) }
            ?: runMessages.getMessage("debug.memory.loading")
        context.memoryRequests.trySend(MemoryRequest(start) { rows ->
            if (request == revision) {
                model.items = rows
                displayedSuspension = context.number
                status.text = runMessages.getMessage("debug.memory.current", context.number)
            }
        })
    }

    override fun dispose() {
        disposed = true
        revision++
    }

    private fun column(key: String, value: (MemoryRow) -> String) =
        object : ColumnInfo<MemoryRow, String>(runMessages.getMessage(key)) {
            override fun valueOf(item: MemoryRow) = value(item)
        }
}
