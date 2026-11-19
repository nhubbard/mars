package edu.missouristate.mars.plugin.tools

import com.intellij.ide.setToolTipText
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.observable.util.whenFocusGained
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ColorPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.debug.parseMemoryAddress
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Base
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Frame
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Mode
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Options
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

internal class MemoryGridView(
    private val project: Project, private val mode: Mode, private val model: (MarsProcessHandler) -> MemoryGridDisplay,
) : Disposable {
    private val selector = MarsToolExecutionSelector(project, ::selectionChanged)
    private val width = sizeSelector(MemoryGridDisplay.displayWidths)
    private val height = sizeSelector(MemoryGridDisplay.displayHeights)
    private val unitWidth = ComboBox(CollectionComboBoxModel(MemoryGridDisplay.unitSizes.toMutableList()))
    private val unitHeight = ComboBox(CollectionComboBoxModel(MemoryGridDisplay.unitSizes.toMutableList()))
    private var addresses = emptyMap<Base, Long>()
    private val base = ComboBox(CollectionComboBoxModel(baseChoices().toMutableList())).apply {
        renderer = textListCellRenderer("") { choice ->
            val name = toolsMessages.getMessage(choice.messageKey)
            addresses[choice]?.let { toolsMessages.getMessage("bitmap.baseChoice", formatToolHex(it), name) } ?: name
        }
    }
    private val customAddress = JBTextField("0x0", 20)
    private val apply = JButton(toolsMessages.getMessage("bitmap.apply"))
    private val toggle = JButton()
    private val open = JButton(toolsMessages.getMessage("bitmap.open"))
    private lateinit var settings: CollapsibleRow
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private val blank = Frame(mode.defaults(), null)
    private val inspectionHint = toolsMessages.getMessage(mode.inspectionHintKey)
    private lateinit var inspection: JEditorPane
    private val canvas = if (mode == Mode.REFERENCES) {
        MemoryGridCanvas(blank, mode.nameKey, inspectionHint, ::describeCell) { inspection.text = it }
    } else null
    private var selected: MemoryGridDisplay? = null
    private val reference = canvas?.let(::ReferenceControls)

    private val controls = marsToolPanel {
        marsExecutionRow(selector.component)
        row {
            cell(toggle)
            cell(reset)
        }
        collapsibleGroup(toolsMessages.getMessage("bitmap.configuration"), indent = false) {
            row { cell(base).label(toolsMessages.getMessage("bitmap.base"), LabelPosition.TOP) }
            row {
                cell(customAddress).label(toolsMessages.getMessage("bitmap.custom.address"), LabelPosition.TOP)
                    .validationOnInput { field ->
                        if (selected == null || base.selectedItem != Base.CUSTOM || parseGridAddress(field.text, selected) != null) null
                        else ValidationInfo(toolsMessages.getMessage("bitmap.custom.invalid"), field)
                    }
            }
            row {
                panel { row { cell(width).label(toolsMessages.getMessage("bitmap.width"), LabelPosition.TOP) } }
                panel { row { cell(height).label(toolsMessages.getMessage("bitmap.height"), LabelPosition.TOP) } }
            }
            row {
                panel { row { cell(unitWidth).label(toolsMessages.getMessage("bitmap.unitWidth"), LabelPosition.TOP) } }
                panel { row { cell(unitHeight).label(toolsMessages.getMessage("bitmap.unitHeight"), LabelPosition.TOP) } }
            }
            reference?.let { controls ->
                row { cell(controls.words).label(toolsMessages.getMessage("references.words"), LabelPosition.TOP) }
                row { cell(controls.threshold).label(toolsMessages.getMessage("references.threshold"), LabelPosition.TOP) }
                    .topGap(TopGap.SMALL)
                row {
                    cell(controls.color)
                    cell(controls.applyColor)
                }
                row { cell(controls.grid) }
            }
            row { cell(apply) }
        }.also {
            settings = it
            it.expanded = false
        }
        if (mode == Mode.BITMAP) row { cell(open) }
        if (canvas != null) {
            row { cell(canvas).align(Align.FILL).resizableColumn() }.resizableRow()
            row { inspection = text(inspectionHint).resizableColumn().component }.layout(RowLayout.INDEPENDENT)
        }
        row { comment(toolsMessages.getMessage(mode.helpKey)).resizableColumn() }.layout(RowLayout.INDEPENDENT)
    }

    val component = ScrollPaneFactory.createScrollPane(controls, true).apply { horizontalScrollBarPolicy = HORIZONTAL_SCROLLBAR_NEVER }

    init {
        UIUtil.uiTraverser(component.viewport.view).filter(JComponent::class.java).forEach { control ->
            control.whenFocusGained(this) {
                if (canvas != null && control === canvas) {
                    // The initial inspection can change the height of the wrapping text.
                    inspection.parent.doLayout()
                    val bounds = Rectangle(0, 0, canvas.width, canvas.height)
                    bounds.add(SwingUtilities.convertRectangle(inspection.parent, inspection.bounds, canvas))
                    canvas.scrollRectToVisible(bounds)
                } else {
                    control.scrollRectToVisible(Rectangle(0, 0, control.width, control.height))
                }
            }
        }
        open.addActionListener { selector.process?.let { openBitmapDisplay(project, it, focus = true) } }
        base.addActionListener { customAddress.isEnabled = selected != null && base.selectedItem == Base.CUSTOM }
        apply.addActionListener {
            val address = if (base.selectedItem == Base.CUSTOM) parseGridAddress(customAddress.text, selected) ?: return@addActionListener
                else selected!!.controls().options.customAddress
            selected!!.configure(Options(
                width.selectedItem as Int, height.selectedItem as Int,
                unitWidth.selectedItem as Int, unitHeight.selectedItem as Int, base.selectedItem as Base,
                (reference?.words?.selectedItem as Int?) ?: 1, customAddress = address,
            ))
            render()
        }
        toggle.addActionListener {
            val display = selected!!
            display.setConnected(!display.controls().connected)
            render()
        }
        reset.addActionListener {
            selected!!.reset()
            selectionChanged()
        }
        controls.registerValidators(this)
        refresh()
        selectionChanged()
    }

    @Suppress("UnstableApiUsage")
    private val refreshJob = component.launchOnShow("Refresh MARS memory display") {
        while (true) {
            withContext(Dispatchers.EDT) { refresh() }
            delay(200.milliseconds)
        }
    }

    private fun sizeSelector(bitmapSizes: List<Int>): ComboBox<Int> {
        val sizes = if (mode == Mode.BITMAP) bitmapSizes else MemoryGridDisplay.displaySizes
        return ComboBox(CollectionComboBoxModel(sizes.toMutableList()))
    }

    fun showSettings(process: MarsProcessHandler) {
        selector.select(process)
        settings.expanded = true
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
            canvas?.clearInspection()
        }
        val controls = current?.controls()
        val options = controls?.options ?: blank.options
        width.selectedItem = options.width
        height.selectedItem = options.height
        unitWidth.selectedItem = options.unitWidth
        unitHeight.selectedItem = options.unitHeight
        updateBaseChoices(controls?.addresses.orEmpty())
        customAddress.text = formatToolHex(options.customAddress)
        base.selectedItem = options.base
        reference?.let {
            it.words.selectedItem = options.wordsPerCell
            it.syncColor()
        }
        render()
    }

    private fun render() {
        val snapshot = selected?.controls()
        val available = snapshot != null
        for (control in listOf(width, height, unitWidth, unitHeight, base, apply, toggle, reset, open)) control.isEnabled = available
        customAddress.isEnabled = available && base.selectedItem == Base.CUSTOM
        reference?.controls?.forEach { it.isEnabled = available }
        toggle.text = toolsMessages.getMessage(if (snapshot?.connected == true) "counter.disconnect" else "counter.connect")
        updateBaseChoices(snapshot?.addresses.orEmpty())
        canvas?.showFrame(selected?.snapshot()?.frame ?: blank)
    }

    private fun baseChoices(): List<Base> = Base.entries.filter { choice ->
        (mode == Mode.REFERENCES || choice != Base.TEXT && choice != Base.WIDE_TEXT) &&
            (choice != Base.WIDE_DATA && choice != Base.WIDE_TEXT || choice in addresses)
    }

    private fun updateBaseChoices(current: Map<Base, Long>) {
        if (addresses == current) return
        val previous = base.selectedItem
        val oldChoices = baseChoices()
        addresses = current
        val choices = baseChoices()
        if (oldChoices != choices) {
            base.model = CollectionComboBoxModel(choices.toMutableList())
            base.selectedItem = if (previous in choices) previous else mode.defaults().base
        }
        base.repaint()
    }

    override fun dispose() {
        refreshJob.cancel()
        releaseSelection()
    }

    private fun releaseSelection() {
        if (mode == Mode.REFERENCES) selected?.releaseView()
    }

    private fun describeCell(index: Int): String? = selected?.referenceCell(index)?.let {
        toolsMessages.getMessage("references.cell", formatToolHex(it.address), it.count.toString())
    }

    private inner class ReferenceControls(private val gridCanvas: MemoryGridCanvas) {
        val words = ComboBox(CollectionComboBoxModel(MemoryGridDisplay.wordCounts.toMutableList()))
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
            grid.addActionListener { gridCanvas.showGrid(grid.isSelected) }
        }

        private fun thresholdValue() = (threshold.value as Int).toLong()

        // Match the configured RGB value in the color picker.
        @Suppress("UseJBColor")
        fun syncColor() {
            color.selectedColor = selected?.let { Color(it.colorAt(thresholdValue())) }
        }
    }
}

// The display and its overlays use fixed colors for contrast against simulated pixels.
@Suppress("UseJBColor")
internal class MemoryGridCanvas(
    private var frame: Frame, nameKey: String, private val inspectionHint: String, private val cellDetails: (Int) -> String?,
    private val inspectionChanged: (String) -> Unit,
) : JPanel() {
    private var showGrid = false
    private var inspected: Point? = null

    init {
        minimumSize = JBUI.size(128, 128)
        preferredSize = minimumSize
        getAccessibleContext().accessibleName = toolsMessages.getMessage(nameKey)
        isFocusable = true
        setToolTipText(HtmlChunk.empty())
        getAccessibleContext().accessibleDescription = inspectionHint
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                if (inspected == null) inspect(Point())
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val cell = cellAt(event.point) ?: return
                requestFocusInWindow()
                inspect(cell)
            }
        })
        bindArrow(KeyEvent.VK_LEFT, -1, 0)
        bindArrow(KeyEvent.VK_RIGHT, 1, 0)
        bindArrow(KeyEvent.VK_UP, 0, -1)
        bindArrow(KeyEvent.VK_DOWN, 0, 1)
    }

    private fun bindArrow(key: Int, dx: Int, dy: Int) {
        registerKeyboardAction({
            val current = inspected ?: Point()
            inspect(Point(current.x + dx, current.y + dy))
        }, KeyStroke.getKeyStroke(key, 0), WHEN_FOCUSED)
    }

    private fun inspect(cell: Point) {
        val column = cell.x.coerceIn(0, frame.options.columns - 1)
        val row = cell.y.coerceIn(0, frame.options.rows - 1)
        inspected = Point(column, row)
        showInspection(cellDetails(row * frame.options.columns + column)
            ?: toolsMessages.getMessage("references.inspect.unmapped"))
        repaint()
    }

    fun clearInspection() {
        if (inspected == null) return
        inspected = null
        showInspection(inspectionHint)
        repaint()
    }

    private fun showInspection(text: String) {
        val accessibility = accessibleContext
        if (accessibility.accessibleDescription == text) return
        accessibility.accessibleDescription = text
        inspectionChanged(text)
    }

    fun showFrame(value: Frame) {
        if (frame === value) return
        frame = value
        inspected?.let(::inspect)
        repaint()
    }

    fun showGrid(value: Boolean) {
        showGrid = value
        repaint()
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val cell = cellAt(event.point) ?: return null
        return cellDetails(cell.y * frame.options.columns + cell.x)
    }

    private fun cellAt(point: Point): Point? {
        val viewport = viewport()
        if (!viewport.contains(point)) return null
        val column = (point.x - viewport.x) * frame.options.columns / viewport.width
        val row = (point.y - viewport.y) * frame.options.rows / viewport.height
        return Point(column, row)
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
            inspected?.let { drawInspection(g, viewport, it) }
        } finally {
            g.dispose()
        }
    }

    private fun drawInspection(g: Graphics2D, viewport: Rectangle, cell: Point) {
        val x = cell.x * viewport.width / frame.options.columns
        val y = cell.y * viewport.height / frame.options.rows
        val cellWidth = (cell.x + 1) * viewport.width / frame.options.columns - x
        val cellHeight = (cell.y + 1) * viewport.height / frame.options.rows - y
        // Leave the captured color visible even when a cell maps to a single screen pixel.
        val width = cellWidth.coerceAtLeast(JBUI.scale(7))
        val height = cellHeight.coerceAtLeast(JBUI.scale(7))
        val left = viewport.x + x - (width - cellWidth) / 2
        val top = viewport.y + y - (height - cellHeight) / 2
        g.color = Color.WHITE
        g.drawRect(left, top, width - 1, height - 1)
        g.color = Color.BLACK
        g.drawRect(left + 1, top + 1, width - 3, height - 3)
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


internal fun parseGridAddress(text: String, display: MemoryGridDisplay?): Long? =
    parseMemoryAddress(text, Long.MAX_VALUE)?.takeIf { display?.acceptsCustomAddress(it) == true }
