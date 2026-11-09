package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.plugin.tools.BitmapDisplay.Base
import edu.missouristate.mars.plugin.tools.BitmapDisplay.Frame
import edu.missouristate.mars.plugin.tools.BitmapDisplay.Options
import edu.missouristate.mars.util.Binary
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class BitmapDisplayView(project: Project) : Disposable {
    private val selector = MarsToolExecutionSelector(project, ::selectionChanged)
    private val width = ComboBox(BitmapDisplay.displaySizes.toTypedArray())
    private val height = ComboBox(BitmapDisplay.displaySizes.toTypedArray())
    private val unitWidth = ComboBox(BitmapDisplay.unitSizes.toTypedArray())
    private val unitHeight = ComboBox(BitmapDisplay.unitSizes.toTypedArray())
    private var addresses = emptyMap<Base, Int>()
    private val base = ComboBox(Base.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { choice ->
            val name = toolsMessages.getMessage(choice.messageKey)
            addresses[choice]?.let { toolsMessages.getMessage("bitmap.baseChoice", Binary.intToHexString(it), name) } ?: name
        }
    }
    private val apply = JButton(toolsMessages.getMessage("bitmap.apply"))
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val blank = Frame(Options(), null)
    private val canvas = BitmapCanvas(blank)
    private var selected: BitmapDisplay? = null

    val component = panel {
        row(toolsMessages.getMessage("counter.selector")) { cell(selector.component).align(AlignX.FILL).resizableColumn() }
        row(toolsMessages.getMessage("bitmap.base")) { cell(base) }
        row(toolsMessages.getMessage("bitmap.width")) { cell(width) }
        row(toolsMessages.getMessage("bitmap.height")) { cell(height) }
        row(toolsMessages.getMessage("bitmap.unitWidth")) { cell(unitWidth) }
        row(toolsMessages.getMessage("bitmap.unitHeight")) { cell(unitHeight) }
        row {
            cell(apply)
            cell(toggle)
            cell(reset)
        }
        row { cell(canvas).align(Align.FILL).resizableColumn() }.resizableRow()
        row { comment(toolsMessages.getMessage("bitmap.help")) }
    }

    init {
        apply.addActionListener {
            selector.process!!.bitmapDisplay.configure(Options(
                width.selectedItem as Int, height.selectedItem as Int,
                unitWidth.selectedItem as Int, unitHeight.selectedItem as Int, base.selectedItem as Base,
            ))
            render()
        }
        toggle.addActionListener {
            val model = selector.process!!.bitmapDisplay
            model.setConnected(!model.snapshot().connected)
            render()
        }
        reset.addActionListener {
            selector.process!!.bitmapDisplay.reset()
            selectionChanged()
        }
        refresh()
        selectionChanged()
    }

    private val refreshJob = component.launchOnShow("Refresh MARS bitmap display") {
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
        val current = selector.process?.bitmapDisplay
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
        render()
    }

    private fun render() {
        val snapshot = selector.process?.bitmapDisplay?.snapshot()
        val available = snapshot != null
        for (control in listOf(width, height, unitWidth, unitHeight, base, apply, toggle, reset)) control.isEnabled = available
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
}

internal class BitmapCanvas(private var frame: Frame) : JPanel() {
    init {
        getAccessibleContext().accessibleName = toolsMessages.getMessage("bitmap.name")
    }

    fun showFrame(value: Frame) {
        if (frame === value) return
        frame = value
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            val image = frame.image ?: return
            val (widthPixels, heightPixels) = frame.options
            val scale = minOf(width.toDouble() / widthPixels, height.toDouble() / heightPixels)
            val drawWidth = (scale * widthPixels).toInt()
            val drawHeight = (scale * heightPixels).toInt()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.drawImage(image, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null)
        } finally {
            g.dispose()
        }
    }
}
