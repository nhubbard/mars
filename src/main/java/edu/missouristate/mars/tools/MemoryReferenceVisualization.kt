/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

@file:Suppress("DEPRECATION")

package edu.missouristate.mars.tools

import edu.missouristate.mars.mips.hardware.AccessNotice
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.util.Binary
import java.awt.*
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener

class MemoryReferenceVisualization(
    title: String = "$NAME, $VERSION",
    heading: String = HEADING
) : AbstractMarsToolAndApplication(title, heading) {
    companion object {
        private const val NAME = "Memory Reference Visualization"
        private const val VERSION = "Version 1.0"
        private const val HEADING = "Visualizing memory reference patterns"

        @JvmStatic
        fun main(args: Array<String>) =
            MemoryReferenceVisualization().go()
    }

    private lateinit var wordsPerUnitSelector: JComboBox<String>
    private lateinit var visualUnitPixelWidthSelector: JComboBox<String>
    private lateinit var visualUnitPixelHeightSelector: JComboBox<String>
    private lateinit var visualPixelWidthSelector: JComboBox<String>
    private lateinit var visualPixelHeightSelector: JComboBox<String>
    private lateinit var displayBaseAddressSelector: JComboBox<String>
    private lateinit var drawHashMarksSelector: JCheckBox
    private lateinit var drawingArea: Graphics
    private lateinit var canvas: JPanel

    private val emptyBorder = EmptyBorder(4, 4, 4, 4)
    private val countFonts = Font("Times", Font.BOLD, 12)
    private val backgroundColor = Color.WHITE

    private val wordsPerUnitChoices = arrayOf("1", "2", "4", "8", "16", "32", "64", "128", "256", "512", "1024", "2048")
    private val defaultWordsPerUnitIndex = 0
    private val visualUnitPixelWidthChoices = arrayOf("1", "2", "4", "8", "16", "32")
    private val defaultVisualUnitPixelWidthIndex = 4
    private val visualUnitPixelHeightChoices = arrayOf("1", "2", "4", "8", "16", "32")
    private val defaultVisualUnitPixelHeightIndex = 4
    private val displayPixelWidthChoices = arrayOf("64", "128", "256", "512", "1024")
    private val defaultDisplayWidthIndex = 2
    private val displayPixelHeightChoices = arrayOf("64", "128", "256", "512", "1024")
    private val defaultDisplayHeightIndex = 2

    private var unitPixelWidth = visualUnitPixelWidthChoices[defaultVisualUnitPixelWidthIndex].toInt()
    private var unitPixelHeight = visualUnitPixelHeightChoices[defaultVisualUnitPixelHeightIndex].toInt()
    private var wordsPerUnit = wordsPerUnitChoices[defaultWordsPerUnitIndex].toInt()
    private var visualAreaWidthInPixels = displayPixelWidthChoices[defaultDisplayWidthIndex].toInt()
    private var visualAreaHeightInPixels = displayPixelHeightChoices[defaultDisplayHeightIndex].toInt()

    private val defaultCounterColors = arrayOf(
        CounterColor(0, Color.black),
        CounterColor(1, Color.blue),
        CounterColor(2, Color.green),
        CounterColor(3, Color.yellow),
        CounterColor(5, Color.orange),
        CounterColor(10, Color.red)
    )
    private val countTable = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
        20, 30, 40, 50, 100, 200, 300, 400, 500, 1000,
        2000, 3000, 4000, 5000, 10000, 50000, 100000, 500000, 1000000
    )

    private lateinit var displayBaseAddressChoices: Array<String>
    private lateinit var displayBaseAddresses: IntArray
    private var defaultBaseAddressIndex: Int = -1
    private var baseAddress: Int = -1
    private lateinit var theGrid: Grid
    private lateinit var counterColorScale: CounterColorScale

    override val toolName: String = NAME

    override fun addAsObserver() {
        var highAddress = baseAddress + theGrid.rows * theGrid.columns * Memory.WORD_LENGTH_BYTES * wordsPerUnit
        if (baseAddress < 0 && highAddress > -4) highAddress = -4
        addAsObserver(baseAddress, highAddress)
    }

    override fun buildMainDisplayArea(): JComponent {
        val results = JPanel()
        results.add(buildOrganizationArea())
        results.add(buildVisualizationArea())
        return results
    }

    override fun processMipsUpdate(resource: Observable, notice: AccessNotice) {
        incrementReferenceCountForAddress((notice as MemoryAccessNotice).address)
        updateDisplay()
    }

    override fun initializePreGUI() {
        wordsPerUnit = getIntComboBoxSelection(wordsPerUnitSelector)
        theGrid = createNewGrid()
        updateBaseAddress()
    }

    override fun reset() {
        resetCounts()
        updateDisplay()
    }

    override fun updateDisplay() {
        canvas.repaint()
    }

    override fun getHelpComponent(): JComponent {
        val helpContent = """
            Use this program to visualize dynamic memory reference
            patterns in MIPS assembly programs.  It may be run either
            from MARS' Tools menu or as a stand-alone application.  For
            the latter, simply write a small driver to instantiate a
            MemoryReferenceVisualization object and invoke its go() method.
    
            You can easily learn to use this small program by playing with
            it!  For the best animation, set the MIPS program to run in
            timed mode using the Run Speed slider.  Each rectangular unit
            on the display represents one or more memory words (default 1)
            and each time a memory word is accessed by the MIPS program,
            its reference count is incremented then rendered in the color
            assigned to the count value.  You can change the count-color
            assignments using the count slider and color patch.  Select a
            counter value then click on the color patch to change the color.
            This color will apply beginning at the selected count and
            extending up to the next slider-provided count.
    
            Contact Pete Sanderson at psanderson@otterbein.edu with
            questions or comments.
            """.trimIndent()
        val help = JButton("Help")
        help.addActionListener { JOptionPane.showMessageDialog(theWindow, helpContent) }
        return help
    }

    private fun buildOrganizationArea(): JComponent {
        val organization = JPanel(GridLayout(9, 1))

        drawHashMarksSelector = JCheckBox()
        val defaultDrawHashMarks = true
        drawHashMarksSelector.isSelected = defaultDrawHashMarks
        drawHashMarksSelector.addActionListener { updateDisplay() }
        wordsPerUnitSelector = JComboBox(wordsPerUnitChoices)
        wordsPerUnitSelector.isEditable = false
        wordsPerUnitSelector.background = backgroundColor
        wordsPerUnitSelector.selectedIndex = defaultWordsPerUnitIndex
        wordsPerUnitSelector.toolTipText = "Number of memory words represented by one visualization element (rectangle)"
        wordsPerUnitSelector.addActionListener {
            wordsPerUnit = getIntComboBoxSelection(wordsPerUnitSelector)
            reset()
        }

        visualUnitPixelWidthSelector = JComboBox<String>(visualUnitPixelWidthChoices)
        visualUnitPixelWidthSelector.setEditable(false)
        visualUnitPixelWidthSelector.setBackground(backgroundColor)
        visualUnitPixelWidthSelector.setSelectedIndex(defaultVisualUnitPixelWidthIndex)
        visualUnitPixelWidthSelector.setToolTipText("Width in pixels of rectangle representing memory access")
        visualUnitPixelWidthSelector.addActionListener {
            unitPixelWidth = getIntComboBoxSelection(visualUnitPixelWidthSelector)
            theGrid = createNewGrid()
            updateDisplay()
        }

        visualUnitPixelHeightSelector = JComboBox<String>(visualUnitPixelHeightChoices)
        visualUnitPixelHeightSelector.setEditable(false)
        visualUnitPixelHeightSelector.setBackground(backgroundColor)
        visualUnitPixelHeightSelector.setSelectedIndex(defaultVisualUnitPixelHeightIndex)
        visualUnitPixelHeightSelector.setToolTipText("Height in pixels of rectangle representing memory access")
        visualUnitPixelHeightSelector.addActionListener {
            unitPixelHeight = getIntComboBoxSelection(visualUnitPixelHeightSelector)
            theGrid = createNewGrid()
            updateDisplay()
        }

        visualPixelWidthSelector = JComboBox<String>(displayPixelWidthChoices)
        visualPixelWidthSelector.setEditable(false)
        visualPixelWidthSelector.setBackground(backgroundColor)
        visualPixelWidthSelector.setSelectedIndex(defaultDisplayWidthIndex)
        visualPixelWidthSelector.setToolTipText("Total width in pixels of visualization area")
        visualPixelWidthSelector.addActionListener {
            visualAreaWidthInPixels = getIntComboBoxSelection(visualPixelWidthSelector)
            canvas.preferredSize = getDisplayAreaDimension()
            canvas.size = getDisplayAreaDimension()
            theGrid = createNewGrid()
            canvas.repaint()
            updateDisplay()
        }

        visualPixelHeightSelector = JComboBox<String>(displayPixelHeightChoices)
        visualPixelHeightSelector.setEditable(false)
        visualPixelHeightSelector.setBackground(backgroundColor)
        visualPixelHeightSelector.setSelectedIndex(defaultDisplayHeightIndex)
        visualPixelHeightSelector.setToolTipText("Total height in pixels of visualization area")
        visualPixelHeightSelector.addActionListener {
            visualAreaHeightInPixels = getIntComboBoxSelection(visualPixelHeightSelector)
            canvas.preferredSize = getDisplayAreaDimension()
            canvas.size = getDisplayAreaDimension()
            theGrid = createNewGrid()
            canvas.repaint()
            updateDisplay()
        }

        displayBaseAddressSelector = JComboBox(displayBaseAddressChoices)
        displayBaseAddressSelector.isEditable = false
        displayBaseAddressSelector.background = backgroundColor
        displayBaseAddressSelector.selectedIndex = defaultBaseAddressIndex
        displayBaseAddressSelector.toolTipText = "Base address for visualization area (upper left corner)"
        displayBaseAddressSelector.addActionListener {
            // This may also affect what address range we should be registered as an Observer
            // for.  The default (inherited) address range is the MIPS static data segment
            // starting at 0x10010000. To change this requires override of
            // AbstractMarsToolAndApplication.addAsObserver().  The no-argument version of
            // that method is called automatically  when "Connect" button is clicked for MarsTool
            // and when "Assemble and Run" button is clicked for Mars application.
            updateBaseAddress()
            // If display base address is changed while connected to MIPS (this can only occur
            // when being used as a MarsTool), we have to delete ourselves as an observer and re-register.
            if (connectButton != null && connectButton!!.isConnected) {
                deleteAsObserver()
                addAsObserver()
            }
            theGrid = createNewGrid()
            updateDisplay()
        }


        // ALL COMPONENTS FOR "ORGANIZATION" SECTION
        val hashMarksRow = getPanelWithBorderLayout()
        hashMarksRow.border = emptyBorder
        hashMarksRow.add(JLabel("Show unit boundaries (grid marks)"), BorderLayout.WEST)
        hashMarksRow.add(drawHashMarksSelector, BorderLayout.EAST)

        val wordsPerUnitRow = getPanelWithBorderLayout()
        wordsPerUnitRow.border = emptyBorder
        wordsPerUnitRow.add(JLabel("Memory Words per Unit "), BorderLayout.WEST)
        wordsPerUnitRow.add(wordsPerUnitSelector, BorderLayout.EAST)

        val unitWidthInPixelRow = getPanelWithBorderLayout()
        unitWidthInPixelRow.border = emptyBorder
        unitWidthInPixelRow.add(JLabel("Unit Width in Pixels "), BorderLayout.WEST)
        unitWidthInPixelRow.add(visualUnitPixelWidthSelector, BorderLayout.EAST)

        val unitHeightInPixelRow = getPanelWithBorderLayout()
        unitHeightInPixelRow.border = emptyBorder
        unitHeightInPixelRow.add(JLabel("Unit Height in Pixels "), BorderLayout.WEST)
        unitHeightInPixelRow.add(visualUnitPixelHeightSelector, BorderLayout.EAST)

        val widthInPixelRow = getPanelWithBorderLayout()
        widthInPixelRow.border = emptyBorder
        widthInPixelRow.add(JLabel("Display Width in Pixels "), BorderLayout.WEST)
        widthInPixelRow.add(visualPixelWidthSelector, BorderLayout.EAST)

        val heightInPixelRow = getPanelWithBorderLayout()
        heightInPixelRow.border = emptyBorder
        heightInPixelRow.add(JLabel("Display Height in Pixels "), BorderLayout.WEST)
        heightInPixelRow.add(visualPixelHeightSelector, BorderLayout.EAST)

        val baseAddressRow = getPanelWithBorderLayout()
        baseAddressRow.border = emptyBorder
        baseAddressRow.add(JLabel("Base address for display "), BorderLayout.WEST)
        baseAddressRow.add(displayBaseAddressSelector, BorderLayout.EAST)

        val colorChooserControls = ColorChooserControls()

        // Lay 'em out in the grid...
        organization.add(hashMarksRow)
        organization.add(wordsPerUnitRow)
        organization.add(unitWidthInPixelRow)
        organization.add(unitHeightInPixelRow)
        organization.add(widthInPixelRow)
        organization.add(heightInPixelRow)
        organization.add(baseAddressRow)
        organization.add(colorChooserControls.colorChooserRow)
        organization.add(colorChooserControls.countDisplayRow)
        return organization
    }

    private fun buildVisualizationArea(): JComponent {
        canvas = GraphicsPanel()
        canvas.preferredSize = getDisplayAreaDimension()
        canvas.toolTipText = "Memory reference count visualization area"
        return canvas
    }

    private fun initializeDisplayBaseChoices() {
        val descriptions = arrayOf(
            " (text)",
            " (global data)",
            " (\$gp)",
            " (static data)",
            " (heap)",
            " (memory map)"
        )
        displayBaseAddresses = intArrayOf(Memory.textBaseAddress, Memory.dataSegmentBaseAddress, Memory.globalPointer,
            Memory.dataBaseAddress, Memory.heapBaseAddress, Memory.memoryMapBaseAddress)
        displayBaseAddressChoices = Array(displayBaseAddresses.size) {
            "${Binary.intToHexString(displayBaseAddresses[it])}${descriptions[it]}"
        }
        defaultBaseAddressIndex = 3
        baseAddress = displayBaseAddresses[defaultBaseAddressIndex]
    }

    private fun updateBaseAddress() {
        baseAddress = displayBaseAddresses[displayBaseAddressSelector.selectedIndex]
    }

    private fun getDisplayAreaDimension() = Dimension(visualAreaWidthInPixels, visualAreaHeightInPixels)

    private fun resetCounts() = theGrid.reset()

    private fun getIntComboBoxSelection(comboBox: JComboBox<String>): Int =
        (comboBox.selectedItem!! as String).toIntOrNull() ?: 1

    private fun getPanelWithBorderLayout() =
        JPanel(BorderLayout(2, 2))

    private fun createNewGrid() = Grid(
        visualAreaHeightInPixels / unitPixelHeight,
        visualAreaWidthInPixels / unitPixelWidth
    )

    private fun incrementReferenceCountForAddress(address: Int) {
        val offset = (address - baseAddress) / Memory.WORD_LENGTH_BYTES / wordsPerUnit
        theGrid.incrementElement(offset / theGrid.columns, offset % theGrid.columns)
    }

    private inner class GraphicsPanel : JPanel() {
        override fun paint(g: Graphics) {
            paintGrid(g, theGrid)
            if (drawHashMarksSelector.isSelected) paintHashMarks(g, theGrid)
        }

        private fun paintHashMarks(g: Graphics, grid: Grid) {
            g.color = getContrastingColor(counterColorScale.getColor(0))
            var leftX = 0
            val rightX = visualAreaWidthInPixels
            var upperY = 0
            val lowerY = 0
            for (j in 0..<grid.columns) {
                g.drawLine(leftX, upperY, leftX, lowerY)
                leftX += unitPixelWidth
            }
            leftX = 0
            for (i in 0..<grid.rows) {
                g.drawLine(leftX, upperY, rightX, upperY)
                upperY += unitPixelHeight
            }
        }

        private fun paintGrid(g: Graphics, grid: Grid) {
            var upperLeftX = 0
            var upperLeftY = 0
            for (i in 0..<grid.rows) {
                for (j in 0..<grid.columns) {
                    g.color = counterColorScale.getColor(grid.getElement(i, j))
                    g.fillRect(upperLeftX, upperLeftY, unitPixelWidth, unitPixelHeight)
                    upperLeftX += unitPixelWidth
                }
                upperLeftX = 0
                upperLeftY += unitPixelHeight
            }
        }

        private fun getContrastingColor(color: Color) = Color(color.rgb xor 0xFFFFFF)
    }

    private inner class ColorChooserControls {
        private var sliderLabel: JLabel
        private var currentColorButton: JButton
        var colorChooserRow: JPanel
        var countDisplayRow: JPanel
        @Volatile private var counterIndex = 0

        init {
            val countIndexInit = 10
            val colorRangeSlider = JSlider(JSlider.HORIZONTAL, 0, countTable.size - 1, countIndexInit).apply {
                toolTipText = "View or change color associated with each reference count value"
                paintTicks = false
                addChangeListener(ColorChooserListener())
            }
            counterIndex = countIndexInit
            sliderLabel = JLabel(setLabel(countTable[counterIndex])).apply {
                toolTipText = "Reference count values listed on non-linear scale of ${countTable.first()} to ${countTable.last()}"
                horizontalAlignment = JLabel.CENTER
                alignmentX = Component.CENTER_ALIGNMENT
            }
            currentColorButton = JButton("   ").apply {
                toolTipText = "Click here to change the color for the reference count subrange based at the current value"
                background = counterColorScale.getColor(countTable[counterIndex])
                addActionListener {
                    val counterValue = countTable[counterIndex]
                    val highEnd = counterColorScale.getHighEndOfRange(counterValue)
                    val dialogLabel = "Select color for reference color " + if (counterValue == highEnd)
                        "value $counterValue" else "range $counterValue-$highEnd"
                    val newColor = JColorChooser.showDialog(theWindow, dialogLabel, counterColorScale.getColor(counterValue))
                    if (newColor != null && newColor != counterColorScale.getColor(counterValue)) {
                        counterColorScale.insertOrReplace(CounterColor(counterValue, newColor))
                        this.background = newColor
                        updateDisplay()
                    }
                }
            }
            colorChooserRow = JPanel()
            countDisplayRow = JPanel()
            colorChooserRow.add(colorRangeSlider)
            colorChooserRow.add(currentColorButton)
            countDisplayRow.add(sliderLabel)
        }

        private fun setLabel(value: Int): String {
            val spaces = when {
                value >= 100 -> ""
                value in 10..<100 -> " "
                else -> "  "
            }
            return "Counter value $spaces$value"
        }

        private inner class ColorChooserListener : ChangeListener {
            override fun stateChanged(e: ChangeEvent) {
                val source = e.source as JSlider
                if (!source.valueIsAdjusting) {
                    counterIndex = source.value
                } else {
                    val count = countTable[source.value]
                    sliderLabel.text = setLabel(count)
                    currentColorButton.background = counterColorScale.getColor(count)
                }
            }
        }
    }

    private class CounterColorScale(var counterColors: Array<CounterColor>) {
        fun getColor(count: Int): Color {
            var result = counterColors[0].associatedColor
            var index = 0
            while (index < counterColors.size && count >= counterColors[index].colorRangeStart) {
                result = counterColors[index].associatedColor
                index++
            }
            return result
        }

        fun getHighEndOfRange(count: Int): Int {
            var highEnd = Int.MAX_VALUE
            if (count < counterColors.last().colorRangeStart) {
                var index = 0
                while (index < counterColors.size - 1 && count >= counterColors[index].colorRangeStart) {
                    highEnd = counterColors[index + 1].colorRangeStart - 1
                    index++
                }
            }
            return highEnd
        }

        fun insertOrReplace(newColor: CounterColor) {
            val index = counterColors.binarySearch(newColor)
            if (index >= 0) {
                counterColors[index] = newColor
            } else {
                val insertIndex = -index - 1
                val newSortedArray = Array(counterColors.size + 1) { CounterColor(-1, Color.black) }
                System.arraycopy(counterColors, 0, newSortedArray, 0, insertIndex)
                System.arraycopy(counterColors, insertIndex, newSortedArray, insertIndex + 1, counterColors.size - insertIndex)
                newSortedArray[insertIndex] = newColor
                counterColors = newSortedArray
            }
        }
    }

    private data class CounterColor(
        val colorRangeStart: Int,
        val associatedColor: Color
    ) : Comparable<CounterColor> {
        override fun compareTo(other: CounterColor): Int = colorRangeStart - other.colorRangeStart
    }

    private class Grid(val rows: Int, val columns: Int) {
        lateinit var grid: Array<Array<Int>>
            private set

        init {
            reset()
        }

        fun getElementOrNull(row: Int, column: Int): Int? = grid.getOrNull(row)?.getOrNull(column)

        fun getElement(row: Int, column: Int): Int = grid[row][column]

        fun incrementElement(row: Int, column: Int): Int =
            if (row in 0..rows && column in 0..columns) ++grid[row][column] else -1

        fun reset() {
            grid = Array(rows) { Array(columns) { 0 } }
        }
    }
}