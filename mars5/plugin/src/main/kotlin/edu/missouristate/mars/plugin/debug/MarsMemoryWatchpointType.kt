package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import edu.missouristate.mars.mips.hardware.AccessNotice
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JTextField

internal typealias MemoryWatchpoint = XBreakpoint<MarsMemoryWatchpointProperties>

internal class MarsMemoryWatchpointType : XBreakpointType<MemoryWatchpoint, MarsMemoryWatchpointProperties>(
    "MarsMipsMemory", runMessages.getMessage("debug.watchpoint.type"),
) {
    override fun createProperties() = MarsMemoryWatchpointProperties()

    override fun getDisplayText(breakpoint: MemoryWatchpoint): String {
        val range = breakpoint.properties?.range() ?: return runMessages.getMessage("debug.watchpoint.invalid")
        return runMessages.getMessage(
            "debug.watchpoint.display", hexAddress(range.first), hexAddress(range.last), range.mode.title,
        )
    }

    override fun getEditorsProvider(breakpoint: MemoryWatchpoint, project: Project) = MarsDebugEditors
    override fun isAddBreakpointButtonVisible() = true

    override fun addBreakpoint(project: Project, parentComponent: JComponent?): MemoryWatchpoint? {
        val dialog = MarsMemoryWatchpointDialog(project)
        dialog.show()
        if (!dialog.isOK) return null
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        return WriteAction.compute<MemoryWatchpoint, RuntimeException> {
            manager.addBreakpoint(this, dialog.properties())
        }
    }
}

internal enum class MemoryWatchMode(private val messageKey: String) {
    READ("debug.watchpoint.read"),
    WRITE("debug.watchpoint.write"),
    READ_WRITE("debug.watchpoint.read.write");

    val title get() = runMessages.getMessage(messageKey)

    fun accepts(type: Int) = when (this) {
        READ -> type == AccessNotice.READ
        WRITE -> type == AccessNotice.WRITE
        READ_WRITE -> true
    }
}

internal class MarsMemoryWatchpointProperties(
    @get:Attribute var first: Long = -1,
    @get:Attribute var last: Long = -1,
    @get:Attribute var mode: MemoryWatchMode = MemoryWatchMode.READ_WRITE,
) : XBreakpointProperties<MarsMemoryWatchpointProperties>() {
    override fun getState() = this

    override fun loadState(state: MarsMemoryWatchpointProperties) {
        first = state.first
        last = state.last
        mode = state.mode
    }

    fun range(highestAddress: Long = Long.MAX_VALUE) = if (first >= 0 && last <= highestAddress && first <= last) {
        MemoryWatchRange(first, last, mode)
    } else null
}

internal data class MemoryWatchRange(val first: Long, val last: Long, val mode: MemoryWatchMode) {
    fun overlaps(access: MemoryAccessNotice): Boolean {
        val start = access.addressBits
        return mode.accepts(access.accessType) && start in 0..last && first - start < access.length
    }
}

internal fun parseMemoryAddress(text: String, highestAddress: Long = UInt.MAX_VALUE.toLong()): Long? {
    val value = text.trim()
    return (if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toLongOrNull(16) else value.toLongOrNull())
        ?.takeIf { it in 0..highestAddress }
}

private fun hexAddress(address: Long): String =
    if (address <= UInt.MAX_VALUE.toLong()) Binary.intToHexString(address.toInt()) else Binary.longToHexString(address)

internal class MarsMemoryWatchpointDialog(project: Project) : DialogWrapper(project) {
    private lateinit var startField: JTextField
    private lateinit var endField: JTextField
    private lateinit var modeBox: JComboBox<MemoryWatchMode>
    val fields = panel {
        row(runMessages.getMessage("debug.watchpoint.first")) { startField = textField().columns(20).component }
        row(runMessages.getMessage("debug.watchpoint.last")) { endField = textField().columns(20).component }
        row(runMessages.getMessage("debug.watchpoint.mode")) {
            modeBox = comboBox(MemoryWatchMode.entries, textListCellRenderer("") { it.title }).component
        }
    }

    init {
        title = runMessages.getMessage("debug.watchpoint.add")
        init()
    }

    override fun createCenterPanel() = fields
    override fun getPreferredFocusedComponent() = startField

    override fun doValidate(): ValidationInfo? {
        val first = parseMemoryAddress(startField.text, Long.MAX_VALUE)
            ?: return ValidationInfo(runMessages.getMessage("debug.watchpoint.invalid.address"), startField)
        val last = parseMemoryAddress(endField.text, Long.MAX_VALUE)
            ?: return ValidationInfo(runMessages.getMessage("debug.watchpoint.invalid.address"), endField)
        return if (last < first) ValidationInfo(runMessages.getMessage("debug.watchpoint.order"), endField) else null
    }

    fun properties() = MarsMemoryWatchpointProperties(
        parseMemoryAddress(startField.text, Long.MAX_VALUE)!!,
        parseMemoryAddress(endField.text, Long.MAX_VALUE)!!,
        modeBox.selectedItem as MemoryWatchMode,
    )
}
