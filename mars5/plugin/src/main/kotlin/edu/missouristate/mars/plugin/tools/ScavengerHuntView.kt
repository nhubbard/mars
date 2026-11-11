package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.administratorId
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.boardHeight
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.boardWidth
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Round
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.table.AbstractTableModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class ScavengerHuntView(project: Project) : Disposable {
    private var selected: MarsProcessHandler? = null
    private val selector = MarsToolExecutionSelector(project, ::render)
    private val toggle = JButton()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val status = JBLabel()
    private val diagnostic = JBLabel()
    private val board = ScavengerHuntBoard()
    private val scoreboard = ScavengerScoreboard()
    private val table = JBTable(scoreboard).apply {
        preferredScrollableViewportSize = JBDimension(240, 135)
        autoCreateRowSorter = true
        accessibleContext.accessibleName = toolsMessages.getMessage("scavenger.scoreboard")
        emptyText.text = toolsMessages.getMessage("scavenger.waiting")
    }
    private val tableScroll = JBScrollPane(table).apply { minimumSize = JBDimension(0, 84) }

    val component = scrollableToolPanel(this, panel {
        row {
            cell(selector.component).label(toolsMessages.getMessage("counter.selector"), LabelPosition.TOP)
                .align(AlignX.FILL).resizableColumn()
        }
        row {
            cell(toggle)
            cell(reset)
        }
        row { cell(status) }
        row { cell(diagnostic) }
        row { cell(board).align(Align.FILL).resizableColumn() }.resizableRow()
        row {
            cell(tableScroll).align(Align.FILL).resizableColumn()
        }.resizableRow()
        row { comment(toolsMessages.getMessage("scavenger.ports"), maxLineLength = 24) }
    }, mapOf(table to tableScroll))

    init {
        toggle.addActionListener {
            val process = selected!!
            process.setScavengerHuntConnected(!process.scavengerHuntSnapshot()!!.connected)
            render()
        }
        reset.addActionListener {
            selected!!.resetScavengerHunt()
            render()
        }
        refresh()
    }

    private val refreshJob = component.launchOnShow("Refresh MARS Scavenger Hunt") {
        while (true) {
            withContext(Dispatchers.EDT) { refresh() }
            delay(100)
        }
    }

    private fun refresh() {
        selector.refresh()
        render()
    }

    private fun select(process: MarsProcessHandler?) {
        if (process !== selected) {
            selected?.releaseScavengerHuntView()
            selected = process
        }
    }

    private fun render() {
        select(selector.process)
        val snapshot = selected?.scavengerHuntSnapshot()
        val blocked = selected?.marsBot?.snapshot()?.connected == true
        val connected = snapshot?.connected == true
        toggle.isEnabled = snapshot?.available == true && !blocked
        toggle.toolTipText = if (blocked) toolsMessages.getMessage("run.connect.deviceConflict") else null
        toggle.text = toolsMessages.getMessage(if (connected) "counter.disconnect" else "counter.connect")
        reset.isEnabled = connected && !snapshot.resetPending
        val game = snapshot?.game
        val round = game?.round
        status.text = when {
            snapshot?.resetPending == true -> toolsMessages.getMessage("scavenger.reset.pending")
            round == null -> toolsMessages.getMessage("scavenger.waiting")
            else -> {
                val player = if (game.playerId == administratorId) toolsMessages.getMessage("scavenger.admin")
                else toolsMessages.getMessage("scavenger.activePlayer", game.playerId)
                toolsMessages.getMessage("scavenger.status", player, game.turnsRemaining)
            }
        }
        diagnostic.text = snapshot?.diagnostic?.let {
            val reason = toolsMessages.getMessage("scavenger.rejection." + (it.rejection?.name ?: "FOREIGN_WRITE"))
            toolsMessages.getMessage("scavenger.diagnostic", it.address.toUInt().toString(16), reason)
        }.orEmpty()
        diagnostic.toolTipText = diagnostic.text
        scoreboard.show(round)
        board.show(round, status.text)
    }

    override fun dispose() {
        refreshJob.cancel()
        select(null)
    }
}

internal class ScavengerScoreboard : AbstractTableModel() {
    private var round: Round? = null

    fun show(value: Round?) {
        if (round === value) return
        round = value
        fireTableDataChanged()
    }

    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = toolsMessages.getMessage(columns[column])
    override fun getRowCount() = round?.players?.size ?: 0

    override fun getColumnClass(column: Int): Class<*> = when (column) {
        0, 2 -> Int::class.javaObjectType
        4 -> Long::class.javaObjectType
        else -> String::class.java
    }

    override fun getValueAt(row: Int, column: Int): Any? {
        val player = round!!.players[row]
        return when (column) {
            0 -> row
            1 -> toolsMessages.getMessage("scavenger.point", player.position.x, player.position.y)
            2 -> player.energy
            3 -> toolsMessages.getMessage("scavenger.visits", player.visitedMask.countOneBits(), round!!.locations.size)
            else -> player.finishAfterInstructions
        }
    }

    private companion object {
        val columns = listOf("scavenger.player", "scavenger.position", "scavenger.energy", "scavenger.locations", "scavenger.finish")
    }
}

internal class ScavengerHuntBoard : JBPanel<ScavengerHuntBoard>() {
    private var round: Round? = null

    init {
        withPreferredSize(250, 220)
        withMinimumHeight(80)
        getAccessibleContext().accessibleName = toolsMessages.getMessage("scavenger.board")
    }

    fun show(value: Round?, description: String) {
        getAccessibleContext().accessibleDescription = description
        if (round === value) return
        round = value
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val pad = JBUI.scale(10)
        val scale = minOf((width - 2 * pad).toDouble() / boardWidth, (height - 2 * pad).toDouble() / boardHeight)
        if (scale <= 0) return
        val wide = (boardWidth * scale).roundToInt()
        val high = (boardHeight * scale).roundToInt()
        val g = graphics.create() as Graphics2D
        try {
            g.translate((width - wide) / 2, (height - high) / 2)
            g.color = JBColor.background()
            g.fillRect(0, 0, wide, high)
            g.color = JBColor.border()
            g.drawRect(0, 0, wide, high)
            val game = round ?: return
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val radius = JBUI.scale(5)
            game.locations.forEachIndexed { index, point ->
                val x = (point.x * scale).roundToInt()
                val y = (point.y * scale).roundToInt()
                g.color = JBColor.BLUE
                g.fillRect(x - radius, y - radius, radius * 2, radius * 2)
                g.color = JBColor.foreground()
                g.drawString((index + 1).toString(), x + radius, y - radius)
            }
            for ((position, players) in game.players.withIndex().groupBy { it.value.position }) {
                val x = (position.x * scale).roundToInt()
                val y = (position.y * scale).roundToInt()
                val player = players.first()
                g.color = if (player.value.color == 0) JBColor.foreground() else Color(player.value.color)
                g.drawOval(x - radius, y - radius, radius * 2, radius * 2)
                if (players.size == 1) {
                    g.drawString(player.index.toString(), x + radius, y - radius)
                } else {
                    val label = toolsMessages.getMessage("scavenger.group", players.size)
                    g.drawString(label, x - g.fontMetrics.stringWidth(label) / 2, y + radius + g.fontMetrics.ascent)
                }
            }
        } finally {
            g.dispose()
        }
    }
}
