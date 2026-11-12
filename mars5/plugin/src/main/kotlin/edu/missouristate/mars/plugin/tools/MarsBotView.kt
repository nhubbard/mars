package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.plugin.tools.MarsBotDevice.Companion.defaultInstructionsPerTick
import edu.missouristate.mars.plugin.tools.MarsBotDevice.Companion.instructionRange
import edu.missouristate.mars.plugin.tools.MarsBotWorld.Companion.boardSize
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import javax.swing.JButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class MarsBotView(project: Project) : Disposable {
    private var selected: MarsBotDevice? = null
    private val selector = MarsToolExecutionSelector(project, ::render)
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val clockLabel = JBLabel(toolsMessages.getMessage("marsBot.clock"))
    private lateinit var clock: JBTextField
    private lateinit var applyClock: JButton
    private val status = JBLabel()
    private val movement = JBLabel()
    private val ports = JBLabel()
    private val board = MarsBotBoard()

    private val controls = marsToolPanel {
        marsExecutionRow(selector.component)
        row {
            cell(toggle)
            cell(reset)
        }
        row { cell(clockLabel) }
        row {
            clock = intTextField(instructionRange).columns(7).component.apply {
                preferredSize = preferredSize.apply { width = width.coerceAtMost(JBUI.scale(100)) }
                minimumSize = minimumSize.apply { width = JBUI.scale(72) }
            }
            clockLabel.labelFor = clock
            applyClock = button(toolsMessages.getMessage("marsBot.clock.apply")) { updateClock() }.component
        }.layout(RowLayout.INDEPENDENT)
        row { cell(status) }
        row { cell(movement) }
        row { cell(ports) }
        row { cell(board).align(Align.FILL).resizableColumn() }.resizableRow()
    }
    val component = scrollableToolPanel(this, controls, emptyMap())

    init {
        toggle.addActionListener {
            val device = selected!!
            selector.process!!.setMarsBotConnected(!device.snapshot().connected)
            render()
        }
        reset.addActionListener {
            selected!!.reset()
            render()
        }
        clock.text = defaultInstructionsPerTick.toString()
        clock.addActionListener { updateClock() }
        controls.registerValidators(this)
        refresh()
    }

    private val refreshJob = component.launchOnShow("Refresh MARS MarsBot") {
        while (true) {
            withContext(Dispatchers.EDT) { refresh() }
            delay(100)
        }
    }

    private fun updateClock() {
        val count = clock.text.toIntOrNull() ?: return
        if (count in instructionRange) selected?.setInstructionsPerTick(count)
    }

    private fun refresh() {
        selector.refresh()
        render()
    }

    private fun select(current: MarsBotDevice?) {
        if (current !== selected) {
            selected?.releaseView()
            selected = current
            clock.text = (current?.snapshot()?.instructionsPerTick ?: defaultInstructionsPerTick).toString()
        }
    }

    private fun render() {
        select(selector.process?.marsBot)
        val snapshot = selected?.snapshot()
        val blocked = selector.process?.scavengerHuntSnapshot()?.connected == true
        toggle.isEnabled = snapshot?.available == true && !blocked
        toggle.toolTipText = if (blocked) toolsMessages.getMessage("run.connect.deviceConflict") else null
        toggle.text = toolsMessages.getMessage(if (snapshot?.connected == true) "counter.disconnect" else "counter.connect")
        reset.isEnabled = snapshot != null
        clock.isEnabled = toggle.isEnabled
        applyClock.isEnabled = clock.isEnabled

        val world = snapshot?.world
        status.text = world?.let {
            toolsMessages.getMessage("marsBot.status", it.position.x.toInt(), it.position.y.toInt(), it.heading)
        } ?: toolsMessages.getMessage("marsBot.empty")
        movement.text = world?.let {
            toolsMessages.getMessage(
                "marsBot.movement",
                toolsMessages.getMessage(if (it.penDown) "marsBot.pen.down" else "marsBot.pen.up"),
                toolsMessages.getMessage(if (it.moving) "marsBot.moving" else "marsBot.stopped"),
            )
        }.orEmpty()
        ports.text = snapshot?.ports?.let { toolsMessages.getMessage("marsBot.ports", hexAddress(it.heading)) }.orEmpty()
        ports.toolTipText = snapshot?.ports?.let {
            toolsMessages.getMessage("marsBot.portDetails", hexAddress(it.heading), hexAddress(it.pen),
                hexAddress(it.x), hexAddress(it.y), hexAddress(it.move))
        }
        val description = if (movement.text.isEmpty()) status.text else "${status.text}   ${movement.text}"
        board.showWorld(world, description)
    }

    private fun hexAddress(value: Int) = "0x" + value.toUInt().toString(16).padStart(8, '0')

    override fun dispose() {
        refreshJob.cancel()
        select(null)
    }
}

internal class MarsBotBoard : JBPanel<MarsBotBoard>() {
    private var world: MarsBotWorld? = null

    init {
        withPreferredSize(260, 260)
        withMinimumHeight(64)
        getAccessibleContext().accessibleName = toolsMessages.getMessage("marsBot.board")
    }

    fun showWorld(value: MarsBotWorld?, state: String) {
        getAccessibleContext().accessibleDescription = state
        if (value == world) return
        world = value
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val padding = JBUI.scale(8)
        val side = minOf(width, height) - 2 * padding
        if (side <= 0) return
        val g = graphics.create() as Graphics2D
        try {
            g.translate((width - side) / 2.0, (height - side) / 2.0)
            g.color = JBColor.background()
            g.fillRect(0, 0, side, side)
            g.color = JBColor.border()
            g.drawRect(0, 0, side, side)

            val current = world ?: return
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val scale = side.toDouble() / (boardSize - 1)
            g.color = JBColor.foreground()
            g.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            current.trail?.strokes()?.forEach { stroke ->
                g.draw(Line2D.Double(stroke.from.x * scale, stroke.from.y * scale, stroke.to.x * scale, stroke.to.y * scale))
            }

            g.translate(current.position.x * scale, current.position.y * scale)
            g.rotate(Math.toRadians(current.heading.toDouble()))
            val markerScale = JBUIScale.scale(1f).toDouble()
            g.scale(markerScale, markerScale)
            g.color = JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
            g.fill(Path2D.Double().apply {
                moveTo(0.0, -7.0)
                lineTo(5.0, 5.0)
                lineTo(-5.0, 5.0)
                closePath()
            })
        } finally {
            g.dispose()
        }
    }
}
