package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Base
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Frame
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Mode
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Options
import edu.missouristate.mars.util.Binary
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class MemoryGridView(
    project: Project, private val mode: Mode, private val model: (MarsProcessHandler) -> MemoryGridDisplay,
) : Disposable {
    private val selector = MarsToolExecutionSelector(project, ::selectionChanged)
    private val width = ComboBox(MemoryGridDisplay.displaySizes.toTypedArray())
    private val height = ComboBox(MemoryGridDisplay.displaySizes.toTypedArray())
    private val unitWidth = ComboBox(MemoryGridDisplay.unitSizes.toTypedArray())
    private val unitHeight = ComboBox(MemoryGridDisplay.unitSizes.toTypedArray())
    private var addresses = emptyMap<Base, Int>()
    private val base = ComboBox(
        (if (mode == Mode.REFERENCES) Base.entries else Base.entries.filterNot { it == Base.TEXT }).toTypedArray(),
    ).apply {
        renderer = textListCellRenderer("") { choice ->
            val name = toolsMessages.getMessage(choice.messageKey)
            addresses[choice]?.let { toolsMessages.getMessage("bitmap.baseChoice", Binary.intToHexString(it), name) } ?: name
        }
    }
    private val apply = JButton(toolsMessages.getMessage("bitmap.apply"))
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val blank = Frame(mode.defaults(), null)
    private val canvas = MemoryGridCanvas(blank, mode.nameKey, if (mode == Mode.REFERENCES) ::describeCell else null)
    private var selected: MemoryGridDisplay? = null
    private val reference = if (mode == Mode.REFERENCES) ReferenceControls() else null

    val component = panel {
        row(toolsMessages.getMessage("counter.selector")) { cell(selector.component).align(AlignX.FILL).resizableColumn() }
        row(toolsMessages.getMessage("bitmap.base")) { cell(base) }
        row(toolsMessages.getMessage("bitmap.width")) { cell(width) }
        row(toolsMessages.getMessage("bitmap.height")) { cell(height) }
        row(toolsMessages.getMessage("bitmap.unitWidth")) { cell(unitWidth) }
        row(toolsMessages.getMessage("bitmap.unitHeight")) { cell(unitHeight) }
        reference?.let { controls ->
            row(toolsMessages.getMessage("references.words")) { cell(controls.words) }
            row(toolsMessages.getMessage("references.threshold")) { cell(controls.threshold) }
            row {
                cell(controls.color)
                cell(controls.applyColor)
                cell(controls.grid)
            }
        }
        row {
            cell(apply)
            cell(toggle)
            cell(reset)
        }
        row { cell(canvas).align(Align.FILL).resizableColumn() }.resizableRow()
        row { comment(toolsMessages.getMessage(mode.helpKey)) }
    }

    init {
        apply.addActionListener {
            selected!!.configure(Options(
                width.selectedItem as Int, height.selectedItem as Int,
                unitWidth.selectedItem as Int, unitHeight.selectedItem as Int, base.selectedItem as Base,
                (reference?.words?.selectedItem as Int?) ?: 1,
            ))
            render()
        }
        toggle.addActionListener {
            val display = selected!!
            display.setConnected(!display.snapshot().connected)
            render()
        }
        reset.addActionListener {
            selected!!.reset()
            selectionChanged()
        }
        refresh()
        selectionChanged()
    }

    private val refreshJob = component.launchOnShow("Refresh MARS memory display") {
        while (true) {
            withContext(Dispatchers.EDT) { refresh() }
            delay(200)
        }
    }

    private fun refresh() {
        selector.refresh()
        render()
    }

    private fun selectionChanged() {
        val current = selector.process?.let(model)
        if (selected !== current) {
            releaseSelection()
            selected = current
        }
        val options = current?.snapshot()?.frame?.options ?: blank.options
        width.selectedItem = options.width
        height.selectedItem = options.height
        unitWidth.selectedItem = options.unitWidth
        unitHeight.selectedItem = options.unitHeight
        base.selectedItem = options.base
        reference?.let {
            it.words.selectedItem = options.wordsPerCell
            it.syncColor()
        }
        render()
    }

    private fun render() {
        val snapshot = selected?.snapshot()
        val available = snapshot != null
        for (control in listOf(width, height, unitWidth, unitHeight, base, apply, toggle, reset)) control.isEnabled = available
        reference?.controls?.forEach { it.isEnabled = available }
        toggle.text = toolsMessages.getMessage(if (snapshot?.connected == true) "counter.disconnect" else "counter.connect")
        val currentAddresses = snapshot?.addresses.orEmpty()
        if (addresses != currentAddresses) {
            addresses = currentAddresses
            base.repaint()
        }
        canvas.showFrame(snapshot?.frame ?: blank)
    }

    override fun dispose() {
        refreshJob.cancel()
        releaseSelection()
    }

    private fun releaseSelection() {
        selected?.releaseView()
    }

    private fun describeCell(index: Int): String? = selected?.referenceCell(index)?.let {
        toolsMessages.getMessage("references.cell", Binary.intToHexString(it.address), it.count.toString())
    }

    private inner class ReferenceControls {
        val words = ComboBox(MemoryGridDisplay.wordCounts.toTypedArray())
        val threshold = JSpinner(SpinnerNumberModel(0, 0, Int.MAX_VALUE, 1)).apply {
            editor = JSpinner.NumberEditor(this, "0").apply { textField.columns = 10 }
        }
        val color = ColorPanel()
        val applyColor = JButton(toolsMessages.getMessage("references.applyColor"))
        val grid = JBCheckBox(toolsMessages.getMessage("references.grid"))
        val controls = listOf(words, threshold, color, applyColor, grid)

        init {
            threshold.addChangeListener { syncColor() }
            applyColor.addActionListener {
                selected!!.setColor(thresholdValue(), color.selectedColor!!.rgb)
                render()
            }
            grid.addActionListener { canvas.showGrid(grid.isSelected) }
        }

        private fun thresholdValue() = (threshold.value as Int).toLong()

        fun syncColor() {
            color.selectedColor = selected?.let { Color(it.colorAt(thresholdValue())) }
        }
    }
}

internal class MemoryGridCanvas(
    private var frame: Frame, nameKey: String, private val cellDetails: ((Int) -> String?)?,
) : JPanel() {
    private var showGrid = false

    init {
        getAccessibleContext().accessibleName = toolsMessages.getMessage(nameKey)
        if (cellDetails != null) toolTipText = ""
    }

    fun showFrame(value: Frame) {
        if (frame === value) return
        frame = value
        repaint()
    }

    fun showGrid(value: Boolean) {
        showGrid = value
        repaint()
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val details = cellDetails ?: return null
        val viewport = viewport()
        if (!viewport.contains(event.point)) return null
        val column = (event.x - viewport.x) * frame.options.columns / viewport.width
        val row = (event.y - viewport.y) * frame.options.rows / viewport.height
        return details(row * frame.options.columns + column)
    }

    private fun viewport(): Rectangle {
        val (widthPixels, heightPixels) = frame.options
        val scale = minOf(width.toDouble() / widthPixels, height.toDouble() / heightPixels)
        val drawWidth = (scale * widthPixels).toInt()
        val drawHeight = (scale * heightPixels).toInt()
        return Rectangle((width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            val viewport = viewport()
            frame.image?.let {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                g.drawImage(it, viewport.x, viewport.y, viewport.width, viewport.height, null)
            }
            if (showGrid) drawGrid(g, viewport)
        } finally {
            g.dispose()
        }
    }

    private fun drawGrid(g: Graphics2D, viewport: Rectangle) {
        g.color = Color(255, 255, 255, 128)
        for (column in 0 until frame.options.columns) {
            val x = viewport.x + column * viewport.width / frame.options.columns
            g.drawLine(x, viewport.y, x, viewport.y + viewport.height - 1)
        }
        for (row in 0 until frame.options.rows) {
            val y = viewport.y + row * viewport.height / frame.options.rows
            g.drawLine(viewport.x, y, viewport.x + viewport.width - 1, y)
        }
    }
}
