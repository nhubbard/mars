package edu.missouristate.mars.plugin.tools

import com.intellij.execution.impl.ConsoleViewUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.plugin.tools.KeyboardDisplayDevice.Delay
import edu.missouristate.mars.plugin.tools.KeyboardDisplayDevice.Options
import edu.missouristate.mars.plugin.tools.MmioTerminalDisplay.Geometry
import edu.missouristate.mars.plugin.tools.MmioTerminalDisplay.Geometry.Companion.columnRange
import edu.missouristate.mars.plugin.tools.MmioTerminalDisplay.Geometry.Companion.rowRange
import edu.missouristate.mars.util.Binary
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class KeyboardDisplayView(project: Project) : Disposable {
    private val factory = EditorFactory.getInstance()
    private val editor = ConsoleViewUtil.setupConsoleEditor(project, false, false).apply {
        component.minimumSize = JBUI.size(120, 72)
        component.preferredSize = JBUI.size(260, 180)
        contentComponent.accessibleContext.accessibleName = toolsMessages.getMessage("keyboardDisplay.output")
    }
    private val document = editor.document
    private var selected: KeyboardDisplayDevice? = null
    private val selector = MarsToolExecutionSelector(project, ::selectionChanged)
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val keyboard = JBTextField().apply {
        emptyText.text = toolsMessages.getMessage("keyboardDisplay.input.placeholder")
        accessibleContext.accessibleName = toolsMessages.getMessage("keyboardDisplay.input")
    }
    val inputComponent: JComponent get() = keyboard
    private val delayMode = ComboBox(Delay.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { toolsMessages.getMessage("keyboardDisplay.delay.${it.name.lowercase()}") }
    }
    private val instructions = JSpinner(SpinnerNumberModel(5, 1, 1_000_000, 1))
    private val delayedOutput = JBCheckBox(toolsMessages.getMessage("keyboardDisplay.delay.output"))
    private lateinit var columns: JBTextField
    private lateinit var rows: JBTextField
    private lateinit var resize: JButton
    private val output = JBLabel()
    private val ports = JBLabel()
    private val remaining = JBLabel()
    private val emptyDisplay = MmioTerminalDisplay()
    private var displayed: MmioTerminalDisplay? = null
    private var updatingOptions = false

    private val controls = marsToolPanel {
        marsExecutionRow(selector.component)
        row {
            cell(toggle)
            cell(reset)
        }
        row { cell(ports) }
        row { cell(output) }
        row { cell(editor.component).align(Align.FILL).resizableColumn() }.resizableRow()
        row {
            cell(keyboard).label(toolsMessages.getMessage("keyboardDisplay.input"), LabelPosition.TOP)
                .align(AlignX.FILL).resizableColumn()
        }
        row { cell(remaining) }
        collapsibleGroup(toolsMessages.getMessage("keyboardDisplay.settings"), indent = false) {
            row {
                cell(delayMode).label(toolsMessages.getMessage("keyboardDisplay.delay.mode"), LabelPosition.TOP)
                cell(instructions).label(toolsMessages.getMessage("keyboardDisplay.delay.instructions"), LabelPosition.TOP)
            }.layout(RowLayout.INDEPENDENT)
            row { cell(delayedOutput) }
            row {
                columns = intTextField(columnRange).columns(4)
                    .label(toolsMessages.getMessage("keyboardDisplay.columns"), LabelPosition.TOP).component
                rows = intTextField(rowRange).columns(4)
                    .label(toolsMessages.getMessage("keyboardDisplay.rows"), LabelPosition.TOP).component
            }.layout(RowLayout.INDEPENDENT).topGap(TopGap.SMALL)
            row {
                resize = button(toolsMessages.getMessage("keyboardDisplay.resize")) { resizeDisplay() }.component
            }
        }.expanded = false
        row { comment(toolsMessages.getMessage("keyboardDisplay.help")).resizableColumn() }.layout(RowLayout.INDEPENDENT)
    }
    val component = scrollableToolPanel(this, controls, mapOf(editor.contentComponent to editor.component))

    init {
        toggle.addActionListener {
            val device = selected!!
            device.setConnected(!device.snapshot().connected)
            render()
            if (keyboard.isEnabled) keyboard.requestFocusInWindow()
        }
        reset.addActionListener {
            selected!!.reset()
            render()
        }
        keyboard.addKeyListener(object : KeyAdapter() {
            override fun keyTyped(event: KeyEvent) {
                if (!keyboard.isEnabled) return
                selected!!.sendKey(event.keyChar)
                event.consume()
            }
        })
        delayMode.addActionListener { configure() }
        instructions.addChangeListener { configure() }
        delayedOutput.addActionListener { configure() }
        controls.registerValidators(this)
        selector.refresh()
        selectionChanged()
    }

    private val refreshJob = component.launchOnShow("Refresh MARS keyboard and display") {
        while (true) {
            withContext(Dispatchers.EDT) {
                selector.refresh()
                render()
            }
            delay(200)
        }
    }

    private fun selectionChanged() {
        select(selector.process?.keyboardDisplay)
        val snapshot = selected?.snapshot()
        val options = snapshot?.options ?: Options()
        updatingOptions = true
        delayMode.selectedItem = options.delay
        instructions.value = options.instructions
        delayedOutput.isSelected = options.displayAfterDelay
        updatingOptions = false
        syncGeometry(snapshot?.display?.geometry ?: emptyDisplay.geometry)
        render()
    }

    private fun syncGeometry(geometry: Geometry) {
        columns.text = geometry.columns.toString()
        rows.text = geometry.rows.toString()
        output.text = toolsMessages.getMessage("keyboardDisplay.output.size", columns.text, rows.text)
    }

    private fun resizeDisplay() {
        val width = columns.text.toIntOrNull()?.takeIf { it in columnRange } ?: return
        val height = rows.text.toIntOrNull()?.takeIf { it in rowRange } ?: return
        selected!!.reset(Geometry(width, height))
        render()
    }

    private fun select(device: KeyboardDisplayDevice?) {
        if (device !== selected) {
            selected?.releaseView()
            selected = device
        }
    }

    private fun configure() {
        if (updatingOptions) return
        selected!!.configure(Options(delayMode.selectedItem as Delay, instructions.value as Int, delayedOutput.isSelected))
    }

    private fun render() {
        val snapshot = selected?.snapshot()
        val connected = snapshot?.connected == true
        toggle.isEnabled = snapshot?.available == true
        toggle.text = toolsMessages.getMessage(if (connected) "counter.disconnect" else "counter.connect")
        keyboard.isEnabled = toggle.isEnabled && connected
        reset.isEnabled = snapshot != null
        columns.isEnabled = reset.isEnabled
        rows.isEnabled = reset.isEnabled
        resize.isEnabled = reset.isEnabled
        delayMode.isEnabled = toggle.isEnabled
        instructions.isEnabled = toggle.isEnabled
        delayedOutput.isEnabled = toggle.isEnabled
        ports.text = snapshot?.base?.let {
            toolsMessages.getMessage("keyboardDisplay.ports", Binary.intToHexString(it))
        } ?: toolsMessages.getMessage("keyboardDisplay.ports.empty")
        val steps = snapshot?.remaining ?: 0
        remaining.text = if (steps == 0) toolsMessages.getMessage("keyboardDisplay.ready")
        else toolsMessages.getMessage("keyboardDisplay.busy", steps)
        showDisplay(snapshot?.display ?: emptyDisplay)
    }

    private fun showDisplay(display: MmioTerminalDisplay) {
        if (display === displayed) return
        if (display.geometry != displayed?.geometry) syncGeometry(display.geometry)
        displayed = display
        val caret = display.caretOffset
        editor.settings.isUseSoftWraps = caret == null
        document.setText(StringUtil.convertLineSeparators(display.text))
        editor.caretModel.moveToOffset(caret ?: document.textLength)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    override fun dispose() {
        refreshJob.cancel()
        select(null)
        factory.releaseEditor(editor)
    }
}
