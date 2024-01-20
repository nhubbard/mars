/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

@file:Suppress("DEPRECATION")

package edu.missouristate.mars.tools

import edu.missouristate.mars.Globals
import edu.missouristate.mars.UIGlobals
import edu.missouristate.mars.mips.hardware.*
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.simulator.Simulator
import edu.missouristate.mars.util.Binary.intToHexString
import edu.missouristate.mars.venus.dialogs.AbstractFontSettingDialog
import java.awt.*
import java.awt.event.*
import java.util.*
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import javax.swing.text.DefaultCaret
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.exitProcess

/**
 * Simulates a keyboard and display.
 */
class KeyboardAndDisplaySimulator(
    title: String = "$NAME, $VERSION",
    heading: String = HEADING
) : AbstractMarsToolAndApplication(title, heading) {
    companion object {
        private const val NAME = "Keyboard and Display MMIO Simulator"
        private const val VERSION = "Version 1.4"
        private const val HEADING = NAME
        // Fill character for virtual terminal
        private const val VT_FILL = ' '
        // ASCII form feed
        private const val CLEAR_SCREEN = '\u000b'
        // ASCII bell (ding ding!)
        private const val SET_CURSOR_X_Y = '\u0007'

        @JvmStatic private var displayPanelTitle: String = ""
        @JvmStatic private var keyboardPanelTitle: String = ""
        @JvmStatic private val preferredTextAreaDimension = Dimension(400, 200)
        @JvmStatic private val textAreaInsets = Insets(4, 4, 4, 4)
        @JvmStatic private var RECEIVER_CONTROL: Int = -1
        @JvmStatic private var RECEIVER_DATA: Int = -1
        @JvmStatic private var TRANSMITTER_CONTROL: Int = -1
        @JvmStatic private var TRANSMITTER_DATA: Int = -1
        @JvmStatic private val defaultFont = Font(Font.MONOSPACED, Font.PLAIN, 12)

        @JvmStatic
        fun main(args: Array<String>) {
            KeyboardAndDisplaySimulator().go()
        }

        @JvmStatic
        private fun <T> tryOrIncorrectAddress(block: () -> T): T {
            try {
                return block()
            } catch (aee: AddressErrorException) {
                println("Tool author specified incorrect MMIO address! $aee")
                exitProcess(1)
            }
        }

        @JvmStatic
        private fun isReadyBitSet(mmioControlRegister: Int): Boolean = tryOrIncorrectAddress {
            (Globals.memory.get(mmioControlRegister, Memory.WORD_LENGTH_BYTES) and 1) == 1
        }

        @JvmStatic
        private fun readyBitSet(mmioControlRegister: Int): Int = tryOrIncorrectAddress {
            Globals.memory.get(mmioControlRegister, Memory.WORD_LENGTH_BYTES) or 1
        }

        @JvmStatic
        private fun readyBitCleared(mmioControlRegister: Int): Int = tryOrIncorrectAddress {
            Globals.memory.get(mmioControlRegister, Memory.WORD_LENGTH_BYTES) and 2
        }
    }

    private val delayTechniques = arrayOf(
        TransmitterDelayTechnique.FixedLengthDelay(),
        TransmitterDelayTechnique.UniformlyDistributedDelay(),
        TransmitterDelayTechnique.NormallyDistributedDelay()
    )
    private var countingInstructions: Boolean = false
    private var instructionCount: Int = 0
    private var transmitDelayInstructionCountLimit: Int = 0
    private var currentDelayInstructionLimit: Int = 0
    private var intWithCharacterToDisplay: Int = 0
    private var displayAfterDelay: Boolean = true
    private var displayRandomAccessMode: Boolean = false
    private var rows: Int = 0
    private var columns: Int = 0
    private lateinit var updateDisplayBorder: DisplayResizeAdapter

    // FIXME: Why do we have a singleton reference? Ugh... this is annoying
    private val simulator: KeyboardAndDisplaySimulator = this

    private lateinit var display: JTextArea
    private lateinit var displayPanel: JPanel
    private lateinit var delayTechniqueChooser: JComboBox<TransmitterDelayTechnique>
    private lateinit var delayLengthPanel: DelayLengthPanel
    private lateinit var displayAfterDelayCheckBox: JCheckBox
    private lateinit var keyEventAccepter: JTextArea

    override val toolName: String = NAME

    override fun initializePreGUI() {
        RECEIVER_CONTROL = Memory.memoryMapBaseAddress
        RECEIVER_DATA = Memory.memoryMapBaseAddress + 4
        TRANSMITTER_CONTROL = Memory.memoryMapBaseAddress + 8
        TRANSMITTER_DATA = Memory.memoryMapBaseAddress + 12
        displayPanelTitle = "DISPLAY: Store to Transmitter Data ${intToHexString(TRANSMITTER_DATA)}"
        keyboardPanelTitle = "KEYBOARD: Characters typed here are stored to Receiver Data ${intToHexString(RECEIVER_DATA)}"
    }

    override fun addAsObserver() {
        // Marks transmitter control as ready to accept display characters
        updateMMIOControl(TRANSMITTER_CONTROL, readyBitSet(TRANSMITTER_CONTROL))
        // We want to be an observer only of MIPS reads from RECEIVER_DATA and writes to TRANSMITTER_DATA
        addAsObserver(RECEIVER_DATA, RECEIVER_DATA)
        addAsObserver(TRANSMITTER_DATA, TRANSMITTER_DATA)
        // We want to be notified of each instruction execution, because instruction counts is the basis for the delay.
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress)
        addAsObserver(Memory.kernelTextBaseAddress, Memory.kernelTextLimitAddress)
    }

    override fun buildMainDisplayArea(): JComponent {
        val keyboardAndDisplay = JPanel(BorderLayout())
        val both = JSplitPane(JSplitPane.VERTICAL_SPLIT, buildDisplay(), buildKeyboard())
        both.resizeWeight = 0.5
        keyboardAndDisplay.add(both)
        return keyboardAndDisplay
    }

    override fun processMipsUpdate(resource: Observable, notice: AccessNotice) {
        if (notice !is MemoryAccessNotice) return
        // If MIPS program has just read (loaded) the receiver (keyboard) data register,
        // then clear the Ready bit to indicate there is no longer a keystroke available.
        // If Ready bit was initially clear, they'll get the old keystroke -- serves 'em right
        // for not checking!
        if (notice.address == RECEIVER_DATA && notice.accessType == AccessNotice.AccessType.READ)
            updateMMIOControl(RECEIVER_CONTROL, readyBitCleared(RECEIVER_CONTROL))
        // MIPS program has just written (stored) the transmitter (display) data register. If the transmitter
        // Ready bit is clear, the device is not ready yet, so ignore this event -- serves 'em right for not checking!
        // If the transmitter Ready bit is set, then clear it to indicate the display device is processing the
        // character. Also start an instruction counter that will simulate the delay of the slower
        // display device processing the character.
        if (isReadyBitSet(TRANSMITTER_CONTROL) && notice.address == TRANSMITTER_DATA && notice.accessType == AccessNotice.AccessType.WRITE) {
            updateMMIOControl(TRANSMITTER_CONTROL, readyBitCleared(TRANSMITTER_CONTROL))
            intWithCharacterToDisplay = notice.value
            if (!displayAfterDelay) displayCharacter(intWithCharacterToDisplay)
            countingInstructions = true
            instructionCount = 0
            transmitDelayInstructionCountLimit = generateDelay()
        }
        // We have been notified of a MIPS instruction execution.
        // If we are in transmit delay period, increment instruction count and if the limit
        // has been reached, set the transmitter Ready flag to indicate the MIPS program
        // can write another character to the transmitter data register. If the MIPS program
        // has set the Interrupt-Enabled bit, generate an interrupt!
        if (countingInstructions && notice.accessType == AccessNotice.AccessType.READ &&
            (Memory.inTextSegment(notice.address) || Memory.inKernelTextSegment(notice.address))) {
            instructionCount++
            if (instructionCount >= transmitDelayInstructionCountLimit) {
                if (displayAfterDelay) displayCharacter(intWithCharacterToDisplay)
                countingInstructions = false
                val updatedTransmitterControl = readyBitSet(TRANSMITTER_CONTROL)
                updateMMIOControl(TRANSMITTER_CONTROL, updatedTransmitterControl)
                if (updatedTransmitterControl != 1 &&
                    (Coprocessor0.getValue(Coprocessor0.STATUS) and 2) == 0 &&
                    (Coprocessor0.getValue(Coprocessor0.STATUS) and 1) == 1)
                    Simulator.externalInterruptingDevice = Exceptions.EXTERNAL_INTERRUPT_DISPLAY
            }
        }
    }

    /**
     * Method to display the character stored in the low-order byte of
     * the parameter.  We also recognize two non-printing characters:
     * Decimal 12 (Ascii Form Feed) to clear the display
     * Decimal 7 (Ascii Bell) to place the cursor at a specified (x, y) position.
     * of a virtual text terminal.  The position is specified in the high-order
     * 24 bits of the transmitter word (X in 20-31, Y in 8-19).
     * Thus, the parameter is the entire word, not just the low-order byte.
     * Once the latter is performed, the display mode changes to random
     * access, which has repercussions for the implementation of character display.
     */
    private fun displayCharacter(character: Int) {
        val charToDisplay = (character and 0x000000FF).digitToChar()
        if (charToDisplay == CLEAR_SCREEN) {
            initializeDisplay(displayRandomAccessMode)
        } else if (charToDisplay == SET_CURSOR_X_Y) {
            // The first call will activate random access mode.
            // We're using JTextArea, where caret has to be within text.
            // So initialize text to all spaces to fill the JTextArea to its
            // current capacity.  Then set caret.  Subsequent character
            // displays will replace, not append, in the text.
            if (!displayRandomAccessMode) {
                displayRandomAccessMode = true
                initializeDisplay(true)
            }
            // For SET_CURSOR_X_Y, we need data from the rest of the word.
            // High-order 3 bytes are split in half to store (x, y) value.
            // High 12 bits contain X value; the next 12 bits contain Y value.
            var x = (character and -0x100000) ushr 20
            var y = (character and 0x000FFF00) ushr 8
            // If X or Y values are outside current range, set to range limit.
            if (x >= columns) x = columns - 1
            if (y >= rows) y = rows - 1
            // display is a JTextArea whose character positioning in the text is linear.
            // Converting (row,column) to linear position requires knowing how many columns
            // are in each row.  I add one because each row except the last ends with '\n' that
            // does not count as a column but occupies a position in the text string.
            // The values of the rows and columns are set in initializeDisplay().
            display.caretPosition = y * (columns + 1) + x
        } else {
            if (displayRandomAccessMode) {
                try {
                    var caretPosition = display.caretPosition
                    if ((caretPosition + 1) % (columns + 1) == 0) {
                        caretPosition++
                        display.caretPosition = caretPosition
                    }
                    display.replaceRange(charToDisplay.toString(), caretPosition, caretPosition + 1)
                } catch (e: IllegalArgumentException) {
                    display.caretPosition -= 1
                    display.replaceRange(charToDisplay.toString(), display.caretPosition, display.caretPosition + 1)
                }
            } else {
                display.append(charToDisplay.toString())
            }
        }
    }

    override fun initializePostGUI() {
        initializeTransmitDelaySimulator()
        keyEventAccepter.requestFocusInWindow()
    }

    override fun reset() {
        displayRandomAccessMode = false
        initializeTransmitDelaySimulator()
        initializeDisplay(displayRandomAccessMode)
        keyEventAccepter.text = ""
        (displayPanel.border as TitledBorder).title = displayPanelTitle
        displayPanel.repaint()
        keyEventAccepter.requestFocusInWindow()
        updateMMIOControl(TRANSMITTER_CONTROL, readyBitSet(TRANSMITTER_CONTROL))
    }

    /**
     * The display JTextArea (top half) is initialized either to the empty
     * string, or to a string filled with lines of spaces. It will do the
     * latter only if the MIPS program has sent the BELL character (Ascii 7) to
     * the transmitter. This sets the caret (cursor) to a specific (x,y) position
     * on a text-based virtual display. The lines of spaces are necessary because
     * the caret can only be placed at a position within the current text string.
     */
    private fun initializeDisplay(randomAccess: Boolean) {
        var initialText = ""
        if (randomAccess) {
            val textDimensions = getDisplayPanelTextDimensions()
            columns = textDimensions.width
            rows = textDimensions.height
            repaintDisplayPanelBorder()
            val row = VT_FILL.toString().repeat(columns)
            initialText = row + ("\n" + row).repeat(max(0, rows - 1))
        }
        display.text = initialText
        display.caretPosition = 0
    }

    /**
     * Update display window title with current text display capacity (columns and rows)
     * This will be called when the window is resized or the font is changed.
     */
    private fun repaintDisplayPanelBorder() {
        val size = getDisplayPanelTextDimensions()
        val cols = size.width
        val rows = size.height
        val caretPosition = display.caretPosition
        // display position as stream or 2D depending on random access
        val stringCaretPosition = if (displayRandomAccessMode) {
            if (((caretPosition + 1) % (columns + 1) != 0)) {
                "(${caretPosition % (columns + 1)},${caretPosition / (columns + 1)})"
            } else if (((caretPosition + 1) % (columns + 1) == 0) && ((caretPosition / (columns + 1)) + 1 == rows)) {
                "(${caretPosition % (columns + 1) - 1},${caretPosition / (columns + 1)})"
            } else {
                "(0,${(caretPosition / (columns + 1)) + 1})"
            }
        } else "$caretPosition"
        val title = "$displayPanelTitle, cursor $stringCaretPosition, area $cols x $rows"
        (displayPanel.border as TitledBorder).title = title
        displayPanel.repaint()
    }

    /**
     * Calculate text display capacity of the display window. Text dimensions are based
     * on pixel dimensions of the window divided by font size properties.
     */
    private fun getDisplayPanelTextDimensions(): Dimension {
        val areaSize = display.size
        val widthInPixels = areaSize.width
        val heightInPixels = areaSize.height
        val metrics = getFontMetrics(display.font)
        val rowHeight = metrics.height
        val charWidth = metrics.charWidth('m')
        return Dimension(widthInPixels / charWidth - 1, heightInPixels / rowHeight - 1)
    }

    override fun getHelpComponent(): JComponent {
        val helpContent = """
            Keyboard And Display MMIO Simulator
            
            Use this program to simulate Memory-Mapped I/O (MMIO) for a keyboard input device and character display
            output device. It may be run either from MARS' Tools menu or as a stand-alone application. For the latter,
            simply write a driver to instantiate a edu.missouristate.mars.tools.KeyboardAndDisplaySimulator object and
            invoke its go() method.
            
            While the tool is connected to MIPS, each keystroke in the text area causes the corresponding ASCII code to
            be placed in the Receiver Data register (low-order byte of memory word ${intToHexString(RECEIVER_DATA)}),
            and the Ready bit to be set to 1 in the Receiver Control register (low-order bit of
            ${intToHexString(RECEIVER_CONTROL)}). The Ready bit is automatically reset to 0 when the MIPS program reads
            the Receiver Data using an 'lw' instruction.
            
            A program may write to the display area by detecting the Ready bit set (1) in the Transmitter Control
            register (low-order bit of memory word ${intToHexString(TRANSMITTER_CONTROL)}), then storing the ASCII code
            of the character to be displayed in the Transmitter Data register (low-order byte of
            ${intToHexString(TRANSMITTER_DATA)}) using a 'sw' instruction. This triggers the simulated display to clear
            the Ready bit to 0, delay awhile to simulate processing the data, then set the Ready bit back to 1. 
            The delay is based on a count of executed MIPS instructions.
            
            In a polled approach to I/O, a MIPS program idles in a loop, testing the device's Ready bit on each
            iteration until it is set to 1 before proceeding. This tool also supports an interrupt-driven approach which
            requires the program to provide an interrupt handler but allows it to perform useful processing instead of
            idly looping. When the device is ready, it signals an interrupt and the MARS simulator will transfer
            control to the interrupt handler. Note: in MARS, the interrupt handler has to co-exist with the exception
            handler in kernel memory, both having the same entry address. Interrupt-driven I/O is enabled when the MIPS
            program sets the Interrupt-Enable bit in the device's control register. Details below.
            
            Upon setting the Receiver Controller's Ready bit to 1, its Interrupt-Enable bit (bit position 1) is tested.
            If 1, then an External Interrupt will be generated. Before executing the next MIPS instruction, the runtime
            simulator will detect the interrupt, place the interrupt code (0) into bits 2-6 of Coprocessor 0's Cause
            register ($13), set bit 8 to 1 to identify the source as keyboard, place the program counter value (address
            of the NEXT instruction to be executed) into its EPC register ($14), and check to see if an interrupt/trap
            handler is present (looks for instruction code at address 0x80000180). If so, the program counter is set to
            that address. If not, program execution is terminated with a message to the Run I/O tab. The
            Interrupt-Enable bit is 0 by default and has to be set by the MIPS program if interrupt-driven input is
            desired. Interrupt-driven input permits the program to perform useful tasks instead of idling in a loop
            polling the Receiver Ready bit! Very event-oriented. The Ready bit is supposed to be read-only, but in
            MARS, it is not.
            
            A similar test and potential response occurs when the Transmitter Controller's Ready bit is set to 1. 
            This occurs after the simulated delay described above. The only difference is the Cause register bit to
            identify the (simulated) display as external interrupt source is bit position 9 rather than 8. 
            This permits you to write programs that perform interrupt-driven output - the program can perform useful
            tasks while the output device is processing its data. Much better than idling in a loop polling the
            Transmitter Ready bit! The Ready bit is supposed to be read-only, but in MARS, it is not.
            
            IMPORTANT NOTE: The Transmitter Controller Ready bit is set to its initial value of 1 only when you click
            the tool's 'Connect to MIPS' button ('Assemble and Run' in the stand-alone version) or the tool's Reset
            button! If you run a MIPS program and reset it in MARS, the controller's Ready bit is cleared to 0!
            Configure the Data Segment Window to display the MMIO address range so you can directly observe values
            stored in the MMIO addresses given above.
            
            When ASCII 12 (form feed) is stored in the Transmitter Data register, the tool's Display window will be
            cleared following the specified transmission delay.
            
            When ASCII 7 (bell) is stored in the Transmitter Data register, the cursor in the tool's Display window will
            be positioned at the (X,Y) coordinate specified by its high-order 3 bytes, following the specfied
            transmission delay. Place the X position (column) in bit positions 20-31 of the Transmitter Data register
            and place the Y position (row) in bit positions 8-19.  The cursor is not displayed but subsequent
            transmitted characters will be displayed starting at that position. Position (0,0) is at upper left. Why did
            I select the ASCII Bell character? Just for fun!
            
            The dimensions (number of columns and rows) of the virtual text-based terminal are calculated based on the
            display window size and font specifications. This calculation occurs during program execution upon first
            use of the ASCII 7 code. It will not change until the Reset button is clicked, even if the window is
            resized. The window dimensions are included in its title, which will be updated upon window resize or font
            change. No attempt is made to reposition data characters already transmitted by the program. To change the
            dimensions of the virtual terminal, resize the Display window as desired (note there is an adjustable
            splitter between the Display and Keyboard windows) then click the tool's Reset button. Implementation
            detail: the window is implemented by a JTextArea to which text is written as a string. Its caret (cursor)
            position is required to be a position within the string. I simulated a text terminal with random positioning
            by pre-allocating a string of spaces with one space per (X,Y) position and an embedded newLine where each
            line ends. Each character transmitted to the window thus replaces an existing character in the string.
            
            Thanks to Eric Wang at Washington State University, who requested these features to enable use of this
            display as the target for programming MMIO text-based games.
            
            Contact Pete Sanderson at psanderson@otterbein.edu with questions or comments.
            """.trimIndent()
        val help = JButton("Help")
        help.addActionListener {
            val ja = JTextArea(helpContent).apply {
                rows = 80
                columns = 120
                lineWrap = true
                wrapStyleWord = true
            }
            val optionPane = JOptionPane(JScrollPane(ja), JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, arrayOf("Close"))
            val dialog = optionPane.createDialog(theWindow, "Simulating the Keyboard and Display")
            dialog.isModal = false
            dialog.isVisible = true
        }
        return help
    }

    private fun buildDisplay(): JComponent {
        displayPanel = JPanel(BorderLayout())
        val tb = TitledBorder(displayPanelTitle)
        tb.titleJustification = TitledBorder.CENTER
        displayPanel.border = tb
        updateDisplayBorder = DisplayResizeAdapter()
        display = JTextArea().apply {
            font = defaultFont
            isEditable = false
            margin = textAreaInsets
            addComponentListener(updateDisplayBorder)
            addCaretListener { simulator.repaintDisplayPanelBorder() }
        }

        val caret = display.caret as DefaultCaret
        caret.updatePolicy = DefaultCaret.ALWAYS_UPDATE

        val displayScrollPane = JScrollPane(display)
        displayScrollPane.preferredSize = preferredTextAreaDimension

        displayPanel.add(displayScrollPane)

        val displayOptions = JPanel()
        delayTechniqueChooser = JComboBox(delayTechniques).apply {
            toolTipText = "Technique for determining simulated transmitter device processing delay"
            addActionListener { transmitDelayInstructionCountLimit = generateDelay() }
        }
        delayLengthPanel = DelayLengthPanel()
        displayAfterDelayCheckBox = JCheckBox("Display After Delay", true).apply {
            toolTipText = "If checked, transmitter data will not be displayed until after a delay"
            addActionListener { displayAfterDelay = displayAfterDelayCheckBox.isSelected }
        }

        val fontButton = JButton("Font...").apply {
            toolTipText = "Select the font for the display panel"
            addActionListener { FontChanger() }
        }

        displayOptions.add(fontButton)
        displayOptions.add(displayAfterDelayCheckBox)
        displayOptions.add(delayTechniqueChooser)
        displayOptions.add(delayLengthPanel)
        displayPanel.add(displayOptions, BorderLayout.SOUTH)
        return displayPanel
    }

    private fun buildKeyboard(): JComponent {
        val keyboardPanel = JPanel(BorderLayout())
        keyEventAccepter = JTextArea().apply {
            isEditable = true
            font = defaultFont
            margin = textAreaInsets
        }
        val keyAccepterScrollPane = JScrollPane(keyEventAccepter)
        keyAccepterScrollPane.preferredSize = preferredTextAreaDimension
        keyEventAccepter.addKeyListener(KeyboardKeyListener())
        keyboardPanel.add(keyAccepterScrollPane)
        val tb = TitledBorder(keyboardPanelTitle)
        tb.titleJustification = TitledBorder.CENTER
        keyboardPanel.border = tb
        return keyboardPanel
    }

    private fun updateMMIOControl(addr: Int, intValue: Int) {
        updateMMIOControlAndData(addr, intValue, 0, 0, true)
    }

    private fun updateMMIOControlAndData(controlAddr: Int, controlValue: Int, dataAddr: Int, dataValue: Int, controlOnly: Boolean = false) {
        if (isBeingUsedAsAMarsTool || connectButton?.isConnected == true) {
            Globals.memoryAndRegistersLock.withLock {
                try {
                    Globals.memory.setRawWord(controlAddr, controlValue)
                    if (!controlOnly) Globals.memory.setRawWord(dataAddr, dataValue)
                } catch (aee: AddressErrorException) {
                    println("Tool author specified incorrect MMIO address! $aee")
                    exitProcess(1)
                }
            }
            UIGlobals.gui.mainPane.executePane?.let {
                if (it.textSegmentWindow.codeHighlighting) it.dataSegmentWindow.updateValues()
            }
        }
    }

    private fun initializeTransmitDelaySimulator() {
        countingInstructions = false
        instructionCount = 0
        transmitDelayInstructionCountLimit = generateDelay()
    }

    private fun generateDelay(): Int {
        val sliderValue = delayLengthPanel.delayLength
        val technique = delayTechniqueChooser.selectedItem as TransmitterDelayTechnique
        return technique.generateDelay(sliderValue)
    }

    private inner class DisplayResizeAdapter : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent?) {
            getDisplayPanelTextDimensions()
            repaintDisplayPanelBorder()
        }
    }

    private inner class KeyboardKeyListener : KeyListener {
        override fun keyTyped(e: KeyEvent) {
            val updatedReceiverControl = readyBitSet(RECEIVER_CONTROL)
            updateMMIOControlAndData(RECEIVER_CONTROL, updatedReceiverControl, RECEIVER_DATA, e.keyChar.digitToInt() and 0x00000ff)
            if (updatedReceiverControl != -1 &&
                (Coprocessor0.getValue(Coprocessor0.STATUS) and 2) == 0 &&
                (Coprocessor0.getValue(Coprocessor0.STATUS) and 1) == 1) {
                Simulator.externalInterruptingDevice = Exceptions.EXTERNAL_INTERRUPT_KEYBOARD
            }
        }

        override fun keyPressed(e: KeyEvent) {}
        override fun keyReleased(e: KeyEvent) {}
    }

    private inner class DelayLengthPanel : JPanel(BorderLayout()) {
        private val delayIndexMin = 0
        private val delayIndexMax = 40
        private val delayIndexInit = 4

        private val delayTable = intArrayOf(
            1, 2, 3, 4, 5, 10, 20, 30, 40, 50, 100,
            150, 200, 300, 400, 500, 600, 700, 800, 900, 1000,
            1500, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000,
            20000, 40000, 60000, 80000, 100000, 200000, 400000, 600000, 800000, 1000000
        ).map { it.toDouble() }.toTypedArray().toDoubleArray()

        private var sliderLabel: JLabel
        @Volatile private var delayLengthIndex: Int = delayIndexInit

        init {
            val delayLengthSlider = JSlider(JSlider.HORIZONTAL, delayIndexMin, delayIndexMax, delayIndexInit).apply {
                size = Dimension(100, size.height)
                maximumSize = size
                addChangeListener(DelayLengthListener())
            }
            sliderLabel = JLabel(getLabel(delayLengthIndex)).apply {
                horizontalAlignment = JLabel.CENTER
                alignmentX = Component.CENTER_ALIGNMENT
            }
            add(sliderLabel, BorderLayout.NORTH)
            add(delayLengthSlider, BorderLayout.CENTER)
            toolTipText = "Parameter for simulated delay length (MIPS instruction execution count)"
        }

        val delayLength: Double get() = delayTable[delayLengthIndex]

        private fun getLabel(index: Int): String = "Delay length: ${delayTable[index].toInt()} instruction executions"

        private inner class DelayLengthListener : ChangeListener {
            override fun stateChanged(e: ChangeEvent) {
                val source = e.source as JSlider
                if (!source.valueIsAdjusting) {
                    delayLengthIndex = source.value
                    transmitDelayInstructionCountLimit = generateDelay()
                } else {
                    sliderLabel.text = getLabel(source.value)
                }
            }
        }
    }

    sealed class TransmitterDelayTechnique {
        abstract fun generateDelay(parameter: Double): Int

        class FixedLengthDelay : TransmitterDelayTechnique() {
            override fun generateDelay(parameter: Double): Int = parameter.toInt()
            override fun toString(): String = "Fixed transmitter delay, select using slider"
            override fun equals(other: Any?): Boolean = this === other
            override fun hashCode(): Int = System.identityHashCode(this)
        }

        class UniformlyDistributedDelay : TransmitterDelayTechnique() {
            private val random = Random()

            override fun generateDelay(parameter: Double): Int = random.nextInt(parameter.toInt()) + 1
            override fun toString(): String =
                "Uniformly distributed delay, minimum value of 1, maximum controlled by slider"
        }

        class NormallyDistributedDelay : TransmitterDelayTechnique() {
            private val random = Random()

            override fun generateDelay(parameter: Double) = abs(random.nextGaussian() * parameter).toInt() + 1
            override fun toString(): String = "Normally distributed delay: floor(abs(N(0, 1) * slider) + 1)"
        }
    }

    private inner class FontSettingDialog(
        owner: Frame?,
        title: String,
        currentFont: Font
    ) : AbstractFontSettingDialog(owner, title, true, currentFont) {
        private var resultOK = false

        fun showDialog(): Font? {
            resultOK = true
            isVisible = true
            return if (resultOK) font else null
        }

        override fun closeDialog() {
            isVisible = false
            updateDisplayBorder.componentResized(null)
        }

        fun performCancel() {
            resultOK = false
        }

        override fun buildControlPanel(): Component =
            Box.createVerticalBox().apply {
                add(Box.createHorizontalGlue())
                add(JButton("OK").apply {
                    addActionListener {
                        apply(font)
                        closeDialog()
                    }
                })
                add(Box.createHorizontalGlue())
                add(JButton("Cancel").apply {
                    addActionListener {
                        performCancel()
                        closeDialog()
                    }
                })
                add(Box.createHorizontalGlue())
                add(JButton("Reset").apply {
                    addActionListener { reset() }
                })
                add(Box.createHorizontalGlue())
            }

        override fun apply(font: Font) {
            display.font = font
            keyEventAccepter.font = font
        }
    }

    private inner class FontChanger : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val fontDialog = FontSettingDialog(null, "Select Text Font", display.font)
            fontDialog.showDialog()
        }
    }
}