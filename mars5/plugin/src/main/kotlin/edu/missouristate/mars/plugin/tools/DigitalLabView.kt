package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.observable.util.whenFocusGained
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.launchOnShow
import java.awt.Dimension
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JToggleButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class DigitalLabView(project: Project) : Disposable {
    private var selected: DigitalLabDevice? = null
    private val selector = MarsToolExecutionSelector(project, ::selectionChanged)
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val left = SevenSegment("digitalLab.left")
    private val right = SevenSegment("digitalLab.right")
    private val timer = JBLabel()
    private val keypad = JPanel(GridLayout(4, 4, JBUI.scale(4), JBUI.scale(4)))
    private val keys = List(16) { number ->
        JToggleButton(number.toString(16).uppercase()).apply {
            addActionListener {
                selected!!.toggleKey(number)
                render()
            }
            keypad.add(this)
        }
    }

    val component = ScrollPaneFactory.createScrollPane(marsToolPanel {
        marsExecutionRow(selector.component)
        row {
            cell(toggle)
            cell(reset)
        }
        row { label(toolsMessages.getMessage("digitalLab.display")) }
        row {
            cell(left.component)
            cell(right.component)
        }
        row { label(toolsMessages.getMessage("digitalLab.keypad")) }
        row { cell(keypad) }
        row(toolsMessages.getMessage("digitalLab.timer")) { cell(timer) }
        row { comment(toolsMessages.getMessage("digitalLab.help"), maxLineLength = 42) }
    }, true)

    init {
        for (control in listOf(selector.component, toggle, reset) + keys) {
            control.whenFocusGained(this) {
                control.scrollRectToVisible(Rectangle(0, 0, control.width, control.height))
            }
        }
        toggle.addActionListener {
            val device = selected!!
            device.setConnected(!device.snapshot().connected)
            render()
        }
        reset.addActionListener {
            selected!!.reset()
            render()
        }
        selector.refresh()
        selectionChanged()
    }

    private val refreshJob = component.launchOnShow("Refresh MARS Digital Lab Sim") {
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
        select(selector.process?.digitalLab)
        render()
    }

    private fun select(current: DigitalLabDevice?) {
        if (current !== selected) {
            selected?.releaseView()
            selected = current
        }
    }

    private fun render() {
        val snapshot = selected?.snapshot()
        val connected = snapshot?.connected == true
        toggle.isEnabled = snapshot?.available == true
        toggle.text = toolsMessages.getMessage(if (connected) "counter.disconnect" else "counter.connect")
        reset.isEnabled = snapshot != null
        left.showBits(snapshot?.left ?: 0)
        right.showBits(snapshot?.right ?: 0)
        val remaining = snapshot?.timerRemaining ?: 0
        timer.text = if (remaining == 0) toolsMessages.getMessage("digitalLab.timer.off")
        else toolsMessages.getMessage("digitalLab.timer.remaining", remaining)
        for ((number, key) in keys.withIndex()) {
            key.isEnabled = toggle.isEnabled && connected
            key.isSelected = snapshot?.key == number
        }
    }

    override fun dispose() {
        refreshJob.cancel()
        select(null)
    }
}

private class SevenSegment(private val messageKey: String) : JPanel() {
    private val valueLabel = JBLabel()
    private var bits = -1
    val component = panel {
        row { cell(this@SevenSegment) }
        row { cell(valueLabel) }
    }

    init {
        preferredSize = Dimension(JBUI.scale(46), JBUI.scale(73))
        showBits(0)
    }

    fun showBits(value: Int) {
        if (bits == value) return
        bits = value
        val hex = value.toString(16).padStart(2, '0')
        val description = toolsMessages.getMessage(messageKey, hex)
        valueLabel.text = description
        getAccessibleContext().accessibleName = description
        toolTipText = description
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        for ((index, shape) in segments.withIndex()) {
            val x = JBUI.scale(shape[0])
            val y = JBUI.scale(shape[1])
            val width = JBUI.scale(shape[2])
            val height = JBUI.scale(shape[3])
            val arc = JBUI.scale(3)
            if (bits and (1 shl index) != 0) {
                graphics.color = JBColor.RED
                graphics.fillRoundRect(x, y, width, height, arc, arc)
            } else {
                graphics.color = JBColor.foreground()
                graphics.drawRoundRect(x, y, width - 1, height - 1, arc, arc)
            }
        }
    }

    private companion object {
        val segments = listOf(
            intArrayOf(10, 5, 23, 5), intArrayOf(33, 10, 5, 21), intArrayOf(33, 36, 5, 21),
            intArrayOf(10, 57, 23, 5), intArrayOf(5, 36, 5, 21), intArrayOf(5, 10, 5, 21),
            intArrayOf(10, 31, 23, 5), intArrayOf(38, 57, 5, 5),
        )
    }
}
