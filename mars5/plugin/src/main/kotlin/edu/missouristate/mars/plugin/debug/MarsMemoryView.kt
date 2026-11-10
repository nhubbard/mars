package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.frame.XValueModifier.XModificationCallback
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.STACK_POINTER_REGISTER
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.build.MarsMemoryExportService
import edu.missouristate.mars.plugin.build.chooseMemoryExport
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.simulator.BackStepper.MemoryWrite
import java.awt.Font
import java.awt.event.ItemEvent
import java.nio.file.Path
import java.util.HexFormat
import javax.swing.JButton
import javax.swing.JTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BYTES_PER_ROW = 16
private const val ROWS_PER_PAGE = 16
private const val BYTES_PER_PAGE = BYTES_PER_ROW * ROWS_PER_PAGE
private const val ADDRESS_SPACE = 0x1_0000_0000L
private const val LAST_ROW = ADDRESS_SPACE - BYTES_PER_ROW
private val hex = HexFormat.of()

internal data class MemoryRow(val address: Int, val bytes: String, val characters: String, val words: List<Int?>)

internal enum class MemoryRegion(val label: String, val address: (MarsContext) -> Int) {
    PC("debug.memory.region.pc", { it.registerFile.programCounterRegister.valueNoNotify }),
    TEXT("debug.memory.region.text", { it.memory.textBaseAddress }),
    DATA("debug.memory.region.data", { it.memory.dataBaseAddress }),
    HEAP("debug.memory.region.heap", { it.memory.heapBaseAddress }),
    STACK("debug.memory.region.stack", { it.registerFile.getValueNoNotify(STACK_POINTER_REGISTER) }),
    KERNEL_TEXT("debug.memory.region.ktext", { it.memory.kernelTextBaseAddress }),
    KERNEL_DATA("debug.memory.region.kdata", { it.memory.kernelDataBaseAddress }),
    MMIO("debug.memory.region.mmio", { it.memory.memoryMapBaseAddress }),
}

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
            start,
            bytes.joinToString(" ") { if (it == null) "--" else hex.toHexDigits(it.toByte()) },
            bytes.joinToString("") {
                val byte = it ?: 0
                if (byte in 0x20..0x7e) byte.toChar().toString() else "."
            },
            words,
        )
    }
}

/** The Debug tab requests bounded reads from the owner of the current suspension. */
internal class MarsMemoryView(private val session: XDebugSession, private val entry: Path) : Disposable {
    private val display = service<MarsDebuggerDisplaySettings>()
    val addressField = JBTextField(12)
    private val go = JButton(runMessages.getMessage("debug.memory.go"))
    private val previous = JButton(runMessages.getMessage("debug.memory.previous"))
    private val next = JButton(runMessages.getMessage("debug.memory.next"))
    private val export = JButton(runMessages.getMessage("debug.memory.export"))
    private val edit = JButton(runMessages.getMessage("debug.memory.edit"))
    private val lastWrite = JButton(runMessages.getMessage("debug.memory.last.write"))
    private val regions = ComboBox(MemoryRegion.entries.toTypedArray()).apply {
        renderer = textListCellRenderer(runMessages.getMessage("debug.memory.region")) { runMessages.getMessage(it.label) }
        selectedItem = null
    }
    private val status = JBLabel()
    private val writeStatus = JBLabel()
    private val model = ListTableModel<MemoryRow>(
        column("debug.memory.address") { display.formatAddress(it.address) },
        column("debug.memory.bytes", MemoryRow::bytes),
        column("debug.memory.ascii", MemoryRow::characters),
        column("debug.memory.words") { row ->
            row.words.joinToString("  ") { word -> word?.let(display::formatValue) ?: "—" }
        },
    )
    private val table = JBTable(model).apply {
        font = Font(Font.MONOSPACED, font.style, font.size)
        val metrics = getFontMetrics(font)
        rowHeight = metrics.height + JBUI.scale(4)
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        val samples = listOf(
            "0x00000000", "00 ".repeat(BYTES_PER_ROW), ".".repeat(BYTES_PER_ROW),
            "-2147483648  ".repeat(BYTES_PER_ROW / Int.SIZE_BYTES),
        )
        for ((index, sample) in samples.withIndex()) {
            columnModel.getColumn(index).preferredWidth = metrics.stringWidth(sample) + JBUI.scale(16)
        }
        columnModel.getColumn(1).cellRenderer = writeRenderer(1, " ")
        columnModel.getColumn(3).cellRenderer = writeRenderer(Int.SIZE_BYTES, "  ")
    }
    private var address: Long? = null
    private var renderedAddress = ""
    private var displayedSuspension: Long? = null
    private var displayedWrite: MemoryWrite? = null
    private var revision = 0L
    private var disposed = false

    val component = panel {
        row(runMessages.getMessage("debug.memory.address")) {
            cell(addressField)
            cell(go)
            cell(previous)
            cell(next)
            cell(regions)
        }
        row {
            cell(edit)
            cell(export)
            cell(lastWrite)
        }
        row { scrollCell(table).align(Align.FILL) }.resizableRow()
        row {
            cell(status)
            cell(writeStatus)
        }
    }

    init {
        repaintMachineHighlights(this, table)
        display.addListener(this) {
            if (addressField.text == renderedAddress) address?.let(::renderAddress)
            renderWriteStatus()
            table.repaint()
        }
        go.addActionListener { navigate() }
        addressField.addActionListener { navigate() }
        previous.addActionListener { move(-BYTES_PER_PAGE) }
        next.addActionListener { move(BYTES_PER_PAGE) }
        export.addActionListener { exportSuspension() }
        edit.addActionListener { editSuspension() }
        lastWrite.addActionListener { showAddress(displayedWrite!!.address()) }
        regions.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) {
                regions.selectedItem = null
                val context = session.suspendContext as? MarsSuspendContext ?: return@addItemListener
                jumpTo(context.memoryRegions.getValue(event.item as MemoryRegion))
            }
        }
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = refreshOnEdt()
            override fun sessionResumed() = refreshOnEdt()
            override fun sessionStopped() = refreshOnEdt()
            override fun settingsChanged() = UIUtil.invokeLaterIfNeeded {
                val context = session.suspendContext as? MarsSuspendContext
                if (context != null && context.lastMemoryWrite != displayedWrite) refresh()
            }
        }, this)
        refresh()
    }

    private fun exportSuspension() {
        val suspension = session.suspendContext as? MarsSuspendContext ?: return
        val project = session.project
        val (options, output) = chooseMemoryExport(project, entry) ?: return
        project.service<MarsMemoryExportService>().exportCapturedInBackground(options, output) {
            suspension.captureForExport(options)
        }
    }

    private fun editSuspension() {
        val suspension = session.suspendContext as? MarsSuspendContext ?: return
        val dialog = MarsMemoryEditDialog(session.project, addressField.text)
        dialog.show()
        if (!dialog.isOK) return

        val target = dialog.address
        val request = MemoryWordEditRequest(target.toInt(), dialog.expression, object : XModificationCallback {
            override fun valueModified() {
                session.rebuildViews()
                jumpTo(target)
            }

            override fun errorOccurred(errorMessage: String) {
                UIUtil.invokeLaterIfNeeded {
                    status.text = errorMessage
                }
            }
        })
        suspension.machineEdits.submitEdit(request, session.suspendContext === suspension)
    }

    private fun refreshOnEdt() = UIUtil.invokeLaterIfNeeded { refresh() }

    private fun move(delta: Int) {
        jumpTo((pageStart(address!!) + delta).coerceIn(0L, LAST_ROW))
    }

    private fun navigate() {
        val parsed = parseMemoryAddress(addressField.text)
        if (parsed == null) {
            revision++
            status.text = runMessages.getMessage("debug.memory.invalid")
            return
        }
        jumpTo(parsed)
    }

    fun showAddress(target: Int) = jumpTo(target.toUInt().toLong())

    private fun jumpTo(target: Long) {
        address = target
        refresh()
    }

    private fun pageStart(target: Long) = target / BYTES_PER_ROW * BYTES_PER_ROW

    private fun renderAddress(target: Long) {
        renderedAddress = display.formatAddress(target.toInt())
        addressField.text = renderedAddress
    }

    private fun refresh() {
        if (disposed) return
        val request = ++revision
        val context = session.suspendContext as? MarsSuspendContext
        val paused = context != null
        addressField.isEnabled = paused
        go.isEnabled = paused
        export.isEnabled = paused
        edit.isEnabled = paused
        regions.isEnabled = paused
        lastWrite.isEnabled = false
        if (context == null) {
            previous.isEnabled = false
            next.isEnabled = false
            status.text = displayedSuspension?.let { runMessages.getMessage("debug.memory.last", it) }
                ?: runMessages.getMessage("debug.memory.pause")
            return
        }

        val target = address ?: context.memoryRegions.getValue(MemoryRegion.DATA)
        address = target
        val start = pageStart(target)
        renderAddress(target)
        previous.isEnabled = start > 0
        next.isEnabled = start < LAST_ROW
        status.text = displayedSuspension?.let { runMessages.getMessage("debug.memory.loading.last", it) }
            ?: runMessages.getMessage("debug.memory.loading")
        context.inspectionRequests.trySend { memory ->
            val rows = memoryPage(memory, start)
            val write = context.lastMemoryWrite
            withContext(Dispatchers.EDT) {
                if (request == revision) {
                    displayedWrite = write
                    lastWrite.isEnabled = write != null
                    renderWriteStatus()
                    model.items = rows
                    displayedSuspension = context.number
                    status.text = runMessages.getMessage("debug.memory.current", context.number)
                }
            }
        }
    }

    private fun renderWriteStatus() {
        writeStatus.text = displayedWrite?.let {
            runMessages.getMessage("debug.memory.last.write.status", display.formatAddress(it.address()), it.length())
        }.orEmpty()
    }

    private fun writeRenderer(bytesPerValue: Int, separator: String) = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val text = value as String
            val write = displayedWrite
            if (write == null) {
                append(text)
                return
            }
            val start = model.getItem(table.convertRowIndexToModel(row)).address.toUInt().toLong()
            val writeStart = write.address().toUInt().toLong()
            val writeEnd = writeStart + write.length()
            val highlight = MarsMachineHighlight.WRITE.tableStyle()
            for ((index, token) in text.split(separator).withIndex()) {
                if (index > 0) append(separator)
                val address = start + index * bytesPerValue
                val written = address < writeEnd && address + bytesPerValue > writeStart
                append(token, if (written) highlight else REGULAR_ATTRIBUTES)
            }
        }
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

internal class MarsMemoryEditDialog(project: Project, initialAddress: String) : DialogWrapper(project) {
    private val addressField = JBTextField(initialAddress, 16)
    private val valueField = JBTextField(20)
    val fields = panel {
        row(runMessages.getMessage("debug.memory.address")) { cell(addressField) }
        row(runMessages.getMessage("debug.memory.edit.value")) { cell(valueField) }
    }

    init {
        title = runMessages.getMessage("debug.memory.edit.title")
        init()
    }

    val address get() = parseMemoryAddress(addressField.text)!!
    val expression get() = valueField.text

    override fun createCenterPanel() = fields
    override fun getPreferredFocusedComponent() = valueField

    override fun doValidate(): ValidationInfo? {
        val address = parseMemoryAddress(addressField.text)
            ?: return ValidationInfo(runMessages.getMessage("debug.memory.invalid"), addressField)
        if (!MemoryState.wordAligned(address.toInt())) {
            return ValidationInfo(runMessages.getMessage("debug.memory.edit.alignment"), addressField)
        }
        return if (valueField.text.isBlank()) ValidationInfo(runMessages.getMessage("debug.memory.edit.word"), valueField) else null
    }
}
