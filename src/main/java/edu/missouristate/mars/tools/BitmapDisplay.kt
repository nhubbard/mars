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
import java.awt.event.ActionEvent
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Bit-mapped display simulator. It can be run either as a stand-alone Java application with
 * access to the MARS package, or through MARS as a tool from its Tools menu. It makes
 * maximum use of methods inherited from its abstract superclass AbstractMarsToolAndApplication.
 */
class BitmapDisplay @JvmOverloads constructor(
    title: String = "$HEADING, $VERSION",
    heading: String = HEADING
) : AbstractMarsToolAndApplication(title, heading) {
    companion object {
        private const val VERSION = "Version 1.0"
        private const val HEADING = "Bitmap Display"

        @JvmStatic
        fun main(args: Array<String>) {
            BitmapDisplay("$HEADING stand-alone, $VERSION", HEADING).go()
        }
    }

    private lateinit var unitPixelWidthSelector: JComboBox<String>
    private lateinit var unitPixelHeightSelector: JComboBox<String>
    private lateinit var pixelWidthSelector: JComboBox<String>
    private lateinit var pixelHeightSelector: JComboBox<String>
    private lateinit var displayBaseAddressSelector: JComboBox<String>
    private lateinit var drawingArea: Graphics
    private lateinit var canvas: JPanel

    private val emptyBorder = EmptyBorder(4, 4, 4, 4)
    private val countFonts = Font("Times", Font.BOLD, 12)
    private val backgroundColor = Color.WHITE

    private val unitPixelWidthChoices = arrayOf("1", "2", "4", "8", "16", "32")
    private val defaultUnitPixelWidthIndex = 0
    private val unitPixelHeightChoices = arrayOf("1", "2", "4", "8", "16", "32")
    private val defaultUnitPixelHeightIndex = 0
    private val pixelWidthChoices = arrayOf("64", "128", "256", "512", "1024")
    private val defaultPixelWidthIndex = 3
    private val pixelHeightChoices = arrayOf("64", "128", "256", "512", "1024")
    private val defaultPixelHeightIndex = 2

    private var unitPixelWidth = unitPixelWidthChoices[defaultUnitPixelWidthIndex].toInt()
    private var unitPixelHeight = unitPixelHeightChoices[defaultUnitPixelHeightIndex].toInt()
    private var pixelWidth = pixelWidthChoices[defaultPixelWidthIndex].toInt()
    private var pixelHeight = pixelHeightChoices[defaultPixelHeightIndex].toInt()

    private lateinit var displayBaseAddressChoices: Array<String>
    private lateinit var displayBaseAddresses: IntArray
    private var defaultBaseAddressIndex = -1
    private var baseAddress = -1

    private lateinit var theGrid: Grid

    override val toolName = HEADING

    /**
     * Override the inherited method, which registers us as an Observer over the static data segment
     * (starting address 0x10010000) only.  This version will register us as an observer over the
     * memory range as selected by the base address combo box and capacity of the visualization display
     * (number of visualization elements times the number of memory words each one represents).
     * It does so by calling the inherited 2-parameter overload of this method.
     * If you use the inherited GUI buttons, this
     * method is invoked when you click "Connect" button on MarsTool or the
     * "Assemble and Run" button on a Mars-based app.
     */
    override fun addAsObserver() {
        var highAddress = baseAddress + theGrid.rows * theGrid.columns * Memory.WORD_LENGTH_BYTES
        // Special case: baseAddress<0 means we're in kernel memory (0x80000000 and up) and most likely
        // in memory map address space (0xffff0000 and up). In this case, we need to make sure the high address
        // does not drop off the high end of the 32-bit address space. The highest allowable word address is 0xfffffffc,
        // which is interpreted in Java int as -4.
        if (baseAddress < 0 && highAddress > -4) highAddress = -4
        addAsObserver(baseAddress, highAddress)
    }

    /**
     * Method that constructs the main display area.  It is organized vertically
     * into two major components: the display configuration, which can be modified
     * using combo boxes, and the visualization display which is updated as the
     * attached MIPS program executes.
     *
     * @return the GUI component containing these two areas
     */
    override fun buildMainDisplayArea(): JComponent {
        val results = JPanel()
        results.add(buildOrganizationArea())
        results.add(buildVisualizationArea())
        return results
    }

    /**
     * Update display when connected MIPS program accesses (data) memory.
     *
     * @param resource       the attached memory
     * @param notice information provided by memory in MemoryAccessNotice object
     */
    override fun processMipsUpdate(resource: Observable, notice: AccessNotice) {
        if (notice.accessType == AccessNotice.AccessType.WRITE)
            updateColorForAddress(notice as MemoryAccessNotice)
    }

    /**
     * Initialize all JComboBox choice structures not already initialized at declaration.
     * Overrides inherited method that does nothing.
     */
    override fun initializePreGUI() {
        initializeDisplayBaseChoices()
        theGrid = Grid(pixelHeight / unitPixelHeight, pixelWidth / unitPixelWidth)
    }

    /**
     * The only post-GUI initialization is to create the initial Grid object based on the default settings
     * of the various combo boxes. Overrides inherited method that does nothing.
     */
    override fun initializePostGUI() {
        theGrid = createNewGrid()
        updateBaseAddress()
    }

    /**
     * Method to reset counters and display when the Reset button selected.
     * Overrides inherited method that does nothing.
     */
    override fun reset() {
        resetCounts()
        updateDisplay()
    }

    /**
     * Updates display immediately after each update (AccessNotice) is processed, after
     * display configuration changes as needed, and after each execution step when Mars
     * is running in timed mode.  Overrides inherited method that does nothing.
     */
    override fun updateDisplay() {
        canvas.repaint()
    }

    /**
     * Overrides default method, to provide a Help button for this tool/app.
     */
    override fun getHelpComponent(): JComponent {
        val helpContent = """
        |Use this program to simulate a basic bitmap display where
        |each memory word in a specified address space corresponds to
        |one display pixel in row-major order starting at the upper left
        |corner of the display.  This tool may be run either from the
        |MARS Tools menu or as a stand-alone application.

        |You can easily learn to use this small program by playing with
        |it!   Each rectangular unit on the display represents one memory
        |word in a contiguous address space starting with the specified
        |base address.  The value stored in that word will be interpreted
        |as a 24-bit RGB color value with the red component in bits 16-23,
        |the green component in bits 8-15, and the blue component in bits 0-7.
        |Each time a memory word within the display address space is written
        |by the MIPS program, its position in the display will be rendered
        |in the color that its value represents.

        |Version 1.0 is very basic and was constructed from the Memory
        |Reference Visualization tool's code.  Feel free to improve it and
        |send me your code for consideration in the next MARS release.

        |Contact Pete Sanderson at psanderson@otterbein.edu with
        |questions or comments.
        """.trimIndent()
        val help = JButton("Help")
        help.addActionListener {
            JOptionPane.showMessageDialog(theWindow, helpContent)
        }
        return help
    }

    /**
     * Draw UI components and layout for the left half of the GUI, where settings are specified.
     */
    private fun buildOrganizationArea(): JComponent {
        val organization = JPanel(GridLayout(8, 1))

        unitPixelWidthSelector = makeComboBox(
            unitPixelWidthChoices,
            defaultUnitPixelWidthIndex,
            "Width in pixels of rectangle representing memory word"
        ) {
            unitPixelWidth = getIntComboBoxSelection(unitPixelWidthSelector)
            theGrid = createNewGrid()
            updateDisplay()
        }

        unitPixelHeightSelector = makeComboBox(
            unitPixelHeightChoices,
            defaultUnitPixelHeightIndex,
            "Height in pixels of rectangle representing memory word"
        ) {
            unitPixelHeight = getIntComboBoxSelection(unitPixelHeightSelector)
            theGrid = createNewGrid()
            updateDisplay()
        }

        pixelWidthSelector = makeComboBox(
            pixelWidthChoices,
            defaultPixelWidthIndex,
            "Total width in pixels of display area"
        ) {
            pixelWidth = getIntComboBoxSelection(pixelWidthSelector)
            canvas.preferredSize = getDisplayAreaDimension()
            canvas.size = getDisplayAreaDimension()
            theGrid = createNewGrid()
            updateDisplay()
        }

        pixelHeightSelector = makeComboBox(
            pixelHeightChoices,
            defaultPixelHeightIndex,
            "Total height in pixels of display area"
        ) {
            pixelHeight = getIntComboBoxSelection(pixelHeightSelector)
            canvas.preferredSize = getDisplayAreaDimension()
            canvas.size = getDisplayAreaDimension()
            theGrid = createNewGrid()
            updateDisplay()
        }

        displayBaseAddressSelector = makeComboBox(
            displayBaseAddressChoices,
            defaultBaseAddressIndex,
            "Base address for display area (upper left corner)"
        ) {
            updateBaseAddress()
            if (connectButton?.isConnected == true) {
                deleteAsObserver()
                addAsObserver()
            }
            theGrid = createNewGrid()
            updateDisplay()
        }

        organization.add(makeOrganizationComponent("Unit Width in Pixels ", unitPixelWidthSelector))
        organization.add(makeOrganizationComponent("Unit Height in Pixels ", unitPixelHeightSelector))
        organization.add(makeOrganizationComponent("Display Width in Pixels ", pixelWidthSelector))
        organization.add(makeOrganizationComponent("Display Height in Pixels ", pixelHeightSelector))
        organization.add(makeOrganizationComponent("Base Address for Display ", displayBaseAddressSelector))

        return organization
    }

    private fun buildVisualizationArea(): JComponent {
        canvas = GraphicsPanel()
        canvas.preferredSize = getDisplayAreaDimension()
        canvas.toolTipText = "Bitmap display area"
        return canvas
    }

    private fun initializeDisplayBaseChoices() {
        val displayBaseAddressArray = intArrayOf(
            Memory.dataSegmentBaseAddress,
            Memory.globalPointer,
            Memory.dataBaseAddress,
            Memory.heapBaseAddress,
            Memory.memoryMapBaseAddress
        )
        val descriptions = arrayOf(
            " (global data)",
            " (\$gp)",
            " (static data)",
            " (heap)",
            " (memory map)"
        )
        displayBaseAddresses = displayBaseAddressArray
        displayBaseAddressChoices = displayBaseAddressArray.zip(descriptions).map {
            "${Binary.intToHexString(it.first)} ${it.second}"
        }.toTypedArray()
        defaultBaseAddressIndex = 2
        baseAddress = displayBaseAddressArray[defaultBaseAddressIndex]
    }

    private fun updateBaseAddress() {
        baseAddress = displayBaseAddresses[displayBaseAddressSelector.selectedIndex]
        // If you want to extend this app to allow user to edit combo box, you can always
        // parse the getSelectedItem() value, because the pre-defined items are all formatted
        // such that the first 10 characters contain the integer's hex value.  And if the
        // value is user-entered, the numeric part cannot exceed 10 characters for a 32-bit
        // address anyway.  So if the value is > 10 characters long, slice off the first
        // 10 and apply Integer.parseInt() to it to get custom base address.
    }

    private fun getDisplayAreaDimension(): Dimension =
        Dimension(pixelWidth, pixelHeight)

    private fun resetCounts() {
        theGrid.reset()
    }

    // TODO: Convert to extension instead
    private fun getIntComboBoxSelection(comboBox: JComboBox<String>): Int =
        comboBox.selectedItem?.toString()?.toIntOrNull() ?: 1

    private fun makeComboBox(choices: Array<String>, defaultIndex: Int, tooltip: String, listener: (ActionEvent) -> Unit) =
        JComboBox(choices).apply {
            isEditable = false
            background = backgroundColor
            selectedIndex = defaultIndex
            toolTipText = tooltip
            addActionListener(listener)
        }

    private fun getPanelWithBorderLayout(): JPanel = JPanel(BorderLayout(2, 2))

    private fun makeOrganizationComponent(labelText: String, component: JComponent): JPanel {
        val row = getPanelWithBorderLayout()
        row.border = emptyBorder
        row.add(JLabel(labelText), BorderLayout.WEST)
        row.add(component, BorderLayout.EAST)
        return row
    }

    private fun createNewGrid(): Grid {
        val rows = pixelHeight / unitPixelHeight
        val columns = pixelWidth / unitPixelWidth
        return Grid(rows, columns)
    }

    private fun updateColorForAddress(notice: MemoryAccessNotice) {
        val address = notice.address
        val value = notice.value
        val offset = (address - baseAddress) / Memory.WORD_LENGTH_BYTES
        try {
            theGrid.setElement(offset / theGrid.columns, offset % theGrid.columns, value)
        } catch (ignored: IndexOutOfBoundsException) {}
    }

    private inner class GraphicsPanel : JPanel() {
        override fun paint(g: Graphics) {
            paintGrid(g, theGrid)
        }

        fun paintGrid(g: Graphics, grid: Grid) {
            var upperLeftX = 0
            var upperLeftY = 0
            for (i in 0..<grid.rows) {
                for (j in 0..<grid.columns) {
                    g.color = grid.getElement(i, j)
                    g.fillRect(upperLeftX, upperLeftY, unitPixelWidth, unitPixelHeight)
                    upperLeftX += unitPixelWidth // faster than multiplying
                }
                // get ready for the next row
                upperLeftX = 0
                upperLeftY += unitPixelHeight // faster than multiplying
            }
        }
    }

    private class Grid(val rows: Int, val columns: Int) {
        lateinit var grid: Array<Array<Color>>
            private set

        init {
            reset()
        }

        fun getElementOrNull(row: Int, column: Int): Color? = grid.getOrNull(row)?.getOrNull(column)

        fun getElement(row: Int, column: Int): Color = grid[row][column]

        fun setElement(row: Int, column: Int, color: Int) {
            grid[row][column] = Color(color)
        }

        fun setElement(row: Int, column: Int, color: Color) {
            grid[row][column] = color
        }

        fun reset() {
            grid = Array(rows) { Array(columns) { Color.BLACK } }
        }
    }
}