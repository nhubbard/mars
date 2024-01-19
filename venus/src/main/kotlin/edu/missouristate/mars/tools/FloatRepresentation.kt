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

import edu.missouristate.mars.*
import edu.missouristate.mars.mips.hardware.AccessNotice
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.hardware.Register
import edu.missouristate.mars.util.Binary
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.*
import javax.swing.border.TitledBorder
import kotlin.math.min

/**
 * Tool to help students learn about IEEE 754 representation of 32-bit
 * floating point values. This representation is used by MIPS "float"
 * directive and instructions and also the Java (and most other languages)
 * "float" data type. As written, it can ALMOST be adapted to 64-bit by
 * changing a few constants.
 */
class FloatRepresentation @JvmOverloads constructor(
    title: String = "$TITLE, $VERSION",
    heading: String = HEADING
) : AbstractMarsToolAndApplication(title, heading) {
    companion object {
        private const val TITLE = "Floating Point Representation"
        private const val VERSION = "Version 1.1"
        private const val HEADING = "32-bit IEEE 754 Floating Point Representation"

        private const val DEFAULT_HEX = "00000000"
        private const val DEFAULT_DECIMAL = "0.0"
        private const val DEFAULT_BINARY_SIGN = "0"
        private const val DEFAULT_BINARY_EXPONENT = "00000000"
        private const val DEFAULT_BINARY_FRACTION = "00000000000000000000000"
        private const val MAX_LENGTH_HEX = 8
        private const val MAX_LENGTH_BINARY_SIGN = 1
        private const val MAX_LENGTH_BINARY_EXPONENT = 8
        private const val MAX_LENGTH_BINARY_FRACTION = 23
        private const val MAX_LENGTH_BINARY_TOTAL =
            MAX_LENGTH_BINARY_SIGN + MAX_LENGTH_BINARY_EXPONENT + MAX_LENGTH_BINARY_FRACTION
        private const val MAX_LENGTH_DECIMAL = 20
        private const val DENORMALIZED_LABEL = "                 significand (denormalized - no 'hidden bit')"
        private const val NORMALIZED_LABEL = "                 significand ('hidden bit' underlined)       "
        private const val EXPANSION_FONT_TAG = "<font size=\"+1\" face=\"Courier\" color=\"#000000\">"
        private const val INSTRUCTION_FONT_TAG =
            "<font size=\"+0\" face=\"Verdana, Arial, Helvetica\" color=\"#000000\">"
        private const val EXPONENT_BIAS = 127
        private const val DEFAULT_INSTRUCTIONS = "Modify any value, then press the Enter key to update all values."
        private const val HTML_SPACES = "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
        private const val ZEROES = "0000000000000000000000000000000000000000000000000000000000000000"

        @JvmStatic private val instructionFont = Font("Arial", Font.PLAIN, 14)
        @JvmStatic private val hexDisplayFont = Font("Courier", Font.PLAIN, 32)
        @JvmStatic private val binaryDisplayFont = Font("Courier", Font.PLAIN, 18)
        @JvmStatic private val decimalDisplayFont = Font("Courier", Font.PLAIN, 18)
        @JvmStatic private val hexDisplayColor = Color.red
        @JvmStatic private val binaryDisplayColor = Color.black
        @JvmStatic private val decimalDisplayColor = Color.blue

        @JvmStatic
        fun main(args: Array<String>) {
            FloatRepresentation("$TITLE, $VERSION", HEADING).go()
        }
    }

    private var attachedRegister: Register? = null
    private lateinit var fpRegisters: Array<Register>

    // Panels to hold binary displays and decorations (labels, arrows)
    private lateinit var binarySignDecoratedDisplay: JPanel
    private lateinit var binaryExponentDecoratedDisplay: JPanel
    private lateinit var binaryFractionDecoratedDisplay: JPanel

    // Editable fields for the hex, binary, and decimal representations
    private lateinit var hexDisplay: JTextField
    private lateinit var decimalDisplay: JTextField
    private lateinit var binarySignDisplay: JTextField
    private lateinit var binaryExponentDisplay: JTextField
    private lateinit var binaryFractionDisplay: JTextField

    // Non-editable fields to display formula translating binary to decimal
    private lateinit var expansionDisplay: JLabel

    private val significandLabel = JLabel(DENORMALIZED_LABEL, JLabel.CENTER)

    private lateinit var binaryToDecimalFormulaGraphic: BinaryToDecimalFormulaGraphic
    private lateinit var instructions: InstructionsPane

    override val toolName: String = TITLE

    /**
     * Override the inherited method, which registers us as an Observer over the static data segment
     * (starting address 0x10010000) only.  This version will register us as observer over the selected
     * floating point register, if any. If no register is selected, it will not do anything.
     * If you use the inherited GUI buttons, this method is invoked when you click "Connect" button
     * on MarsTool or the "Assemble and Run" button on a Mars-based app.
     */
    override fun addAsObserver() = addAsObserver(attachedRegister)

    /**
     * Delete this app/tool as an Observer of the attached register.  This overrides
     * the inherited version which deletes only as an Observer of memory.
     * This method is called when the default "Disconnect" button on a MarsTool is selected or
     * when the MIPS program execution triggered by the default "Assemble and run" on a stand-alone
     * Mars app terminates (e.g. when the button is re-enabled).
     */
    override fun deleteAsObserver() = deleteAsObserver(attachedRegister)

    override fun buildMainDisplayArea() = buildDisplayArea()

    /**
     * Override inherited update() to update display when "attached" register is modified
     * either by MIPS program or by user editing it on the MARS user interface.
     * The latter is the reason for overriding the inherited update() method.
     * The inherited method will filter out notices triggered by the MARS GUI or the user.
     *
     * @param resource     the attached register
     * @param accessNotice information provided by register in RegisterAccessNotice object
     */
    override fun update(resource: Observable, accessNotice: Any) {
        if (accessNotice !is AccessNotice) return
        if (accessNotice.accessType == AccessNotice.AccessType.WRITE)
            updateDisplays(FlavorsOfFloat().fromInt(attachedRegister!!.getValue()))
    }

    /**
     * Method to reset display values to 0 when the Reset button selected.
     * If attached to a MIPS register at the time, the register will be reset as well.
     * Overrides inherited method that does nothing.
     */
    override fun reset() {
        instructions.text = DEFAULT_INSTRUCTIONS
        updateDisplaysAndRegister(FlavorsOfFloat())
    }

    private fun buildDisplayArea(): JComponent {
        // Panel to hold all floating point display and editing components
        val mainPanel = Box.createVerticalBox()
        val leftPanel = JPanel(GridLayout(5, 1, 0, 0))
        val rightPanel = JPanel(GridLayout(5, 1, 0, 0))
        val subMainPanel = Box.createHorizontalBox()
        subMainPanel.add(leftPanel)
        subMainPanel.add(rightPanel)
        mainPanel.add(subMainPanel)

        // Editable display for the hexadecimal version of the float value
        hexDisplay = JTextField(DEFAULT_HEX, MAX_LENGTH_HEX + 1).apply {
            font = hexDisplayFont
            foreground = hexDisplayColor
            horizontalAlignment = JTextField.RIGHT
            toolTipText = "$MAX_LENGTH_HEX-digit hexadecimal (base-16) display"
            isEditable = true
            revalidate()
            addKeyListener(HexDisplayKeystrokeListener(8))
        }

        val hexPanel = JPanel()
        hexPanel.add(hexDisplay)
        // Grid Row: Hexadecimal
        leftPanel.add(hexPanel)

        val hexToBinaryGraphic = HexToBinaryGraphicPanel()
        // Grid Row: Hex-to-binary graphic
        leftPanel.add(hexToBinaryGraphic)

        // Editable display for the binary version of float value.
        // Split into 3 separately editable components (sign, exponent, and fraction).
        binarySignDisplay = JTextField(DEFAULT_BINARY_SIGN, MAX_LENGTH_BINARY_SIGN + 1)
        binaryExponentDisplay = JTextField(DEFAULT_BINARY_EXPONENT, MAX_LENGTH_BINARY_EXPONENT + 1)
        binaryFractionDisplay = BinaryFractionDisplayTextField(DEFAULT_BINARY_FRACTION, MAX_LENGTH_BINARY_FRACTION + 1)

        binarySignDisplay.toolTipText = "The sign bit"
        binaryExponentDisplay.toolTipText = "$MAX_LENGTH_BINARY_EXPONENT-bit exponent"
        binaryFractionDisplay.toolTipText = "$MAX_LENGTH_BINARY_FRACTION-bit fraction"
        listOf(binarySignDisplay, binaryExponentDisplay, binaryFractionDisplay).forEach {
            it.apply {
                font = binaryDisplayFont
                foreground = binaryDisplayColor
                horizontalAlignment = JTextField.RIGHT
                isEditable = true
                revalidate()
            }
        }
        binarySignDisplay.addKeyListener(BinaryDisplayKeystrokeListener(MAX_LENGTH_BINARY_SIGN))
        binaryExponentDisplay.addKeyListener(BinaryDisplayKeystrokeListener(MAX_LENGTH_BINARY_EXPONENT))
        binaryFractionDisplay.addKeyListener(BinaryDisplayKeystrokeListener(MAX_LENGTH_BINARY_FRACTION))

        binarySignDecoratedDisplay = JPanel(BorderLayout()).apply {
            add(binarySignDisplay)
            add(JLabel("Sign", JLabel.CENTER), BorderLayout.SOUTH)
        }
        binaryExponentDecoratedDisplay = JPanel(BorderLayout()).apply {
            add(binaryExponentDisplay)
            add(JLabel("Exponent", JLabel.CENTER), BorderLayout.SOUTH)
        }
        binaryFractionDecoratedDisplay = JPanel(BorderLayout()).apply {
            add(binaryFractionDisplay)
            add(JLabel("Fraction", JLabel.CENTER), BorderLayout.SOUTH)
        }
        val binaryPanel = JPanel().apply {
            listOf(binarySignDecoratedDisplay, binaryExponentDecoratedDisplay, binaryFractionDecoratedDisplay)
                .forEach(::add)
        }

        // Grid row: binary
        leftPanel.add(binaryPanel)

        // Grid row: binary to decimal formula arrows
        binaryToDecimalFormulaGraphic = BinaryToDecimalFormulaGraphic()
        binaryToDecimalFormulaGraphic.background = leftPanel.background
        leftPanel.add(binaryToDecimalFormulaGraphic)

        // Non-editable display for expansion of binary representation
        expansionDisplay = JLabel(FlavorsOfFloat().expansionString).apply {
            font = Font("Monospaced", Font.PLAIN, 12)
            isFocusable = false
            background = leftPanel.background
        }
        val expansionDisplayBox = JPanel(GridLayout(2, 1)).apply {
            add(expansionDisplay)
            add(significandLabel)
        }

        // Grid row: formula mapping binary to decimal
        leftPanel.add(expansionDisplayBox)

        // Editable display for the decimal version of the float value.
        decimalDisplay = JTextField(DEFAULT_DECIMAL, MAX_LENGTH_DECIMAL + 1).apply {
            font = decimalDisplayFont
            foreground = decimalDisplayColor
            horizontalAlignment = JTextField.RIGHT
            toolTipText = "Decimal floating point value"
            margin = Insets(0, 0, 0, 0)
            isEditable = true
            revalidate()
            addKeyListener(DecimalDisplayKeystrokeListener())
        }
        val decimalDisplayBox = Box.createVerticalBox().apply {
            add(Box.createVerticalStrut(5))
            add(decimalDisplay)
            add(Box.createVerticalStrut(15))
        }

        val rightPanelLayout = FlowLayout(FlowLayout.LEFT)
        val place1 = JPanel(rightPanelLayout)
        val place2 = JPanel(rightPanelLayout)
        val place3 = JPanel(rightPanelLayout)
        val place4 = JPanel(rightPanelLayout)

        val hexExplain = JEditorPane("text/html", "$EXPANSION_FONT_TAG&lt;&nbsp;&nbsp;Hexadecimal representation</font>").apply {
            isEditable = false
            isFocusable = false
            foreground = Color.BLACK
            background = place1.background
        }
        val hexToBinExplain = JEditorPane("text/html", "$EXPANSION_FONT_TAG&lt;&nbsp;&nbsp;Each hex digit represents 4 bits</font>").apply {
            isEditable = false
            isFocusable = false
            background = place2.background
        }
        val binExplain = JEditorPane("text/html", "$EXPANSION_FONT_TAG&lt;&nbsp;&nbsp;Binary representation</font>").apply {
            isEditable = false
            isFocusable = false
            background = place3.background
        }
        val binToDecExplain = JEditorPane("text/html", "$EXPANSION_FONT_TAG&lt;&nbsp;&nbsp;Binary-to-decimal conversion</font>").apply {
            isEditable = false
            isFocusable = false
            background = place4.background
        }

        place1.add(hexExplain)
        place2.add(hexToBinExplain)
        place3.add(binExplain)
        place4.add(binToDecExplain)

        // 4 grid rows: explanations
        rightPanel.add(place1)
        rightPanel.add(place2)
        rightPanel.add(place3)
        rightPanel.add(place4)

        // Grid row: decimal
        rightPanel.add(decimalDisplayBox)

        // The main panel is vertical box, instructions get a row.
        val instructionsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        instructions = InstructionsPane(instructionsPanel)
        instructionsPanel.add(instructions)
        instructionsPanel.border = TitledBorder("Instructions")
        mainPanel.add(instructionsPanel)

        // Means of selecting and deselecting an attached floating point register
        fpRegisters = Coprocessor1.registers
        val registerList = arrayOf("None", *fpRegisters.map { it.name }.toTypedArray())
        val registerSelect = JComboBox(registerList).apply {
            selectedIndex = 0
            toolTipText = "Attach to selected FP register"
            addActionListener {
                val cb = it.source as JComboBox<*>
                val selectedIndex = cb.selectedIndex
                if (isObserving) deleteAsObserver()
                if (selectedIndex == 0) {
                    attachedRegister = null
                    updateDisplays(FlavorsOfFloat())
                    instructions.text = "The program is not attached to any FPU register."
                } else {
                    attachedRegister = fpRegisters[selectedIndex - 1]
                    updateDisplays(FlavorsOfFloat().fromInt(attachedRegister!!.getValue()))
                    if (isObserving) addAsObserver()
                    instructions.text = "The program and register ${attachedRegister!!.name} will respond to each " +
                        "other when MIPS programs are connected or running."
                }
            }
        }

        val registerPanel = JPanel(BorderLayout(5, 5))
        val registerAndLabel = JPanel()
        registerAndLabel.add(JLabel("MIPS FPU register: "))
        registerAndLabel.add(registerSelect)
        registerPanel.add(registerAndLabel, BorderLayout.WEST)
        registerPanel.add(JLabel(" "), BorderLayout.NORTH)
        mainPanel.add(registerPanel)
        return mainPanel
    }

    /** If display is attached to a register, then update the register value. */
    private fun updateAnyAttachedRegister(intValue: Int) {
        attachedRegister?.let {
            synchronized(Globals.memoryAndRegistersLock) {
                it.setValue(intValue)
            }
            // Here lies a hack: we want to immediately display the updated register value in MARS,
            // but that code was not written for event-driven update (e.g., Observer) --
            // it was written to poll the registers for their values. So we force it to do so.
            UIGlobals.gui?.registersPane?.coprocessor1Window?.updateRegisters()
        }
    }

    /** Update all components displaying various representations of the 32-bit floating point value. */
    private fun updateDisplays(flavors: FlavorsOfFloat) {
        val hexIndex =
            if ((flavors.hexString[0] == '0' && (flavors.hexString[1] == 'x' || flavors.hexString[1] == 'X'))) 2 else 0;
        hexDisplay.text = flavors.hexString.substring(hexIndex).uppercase()
        binarySignDisplay.text = flavors.binaryString.substring(0, MAX_LENGTH_BINARY_SIGN)
        binaryExponentDisplay.text = flavors.binaryString.substring(
            MAX_LENGTH_BINARY_SIGN,
            MAX_LENGTH_BINARY_SIGN + MAX_LENGTH_BINARY_EXPONENT
        )
        binaryFractionDisplay.text = flavors.binaryString.substring(
            MAX_LENGTH_BINARY_SIGN + MAX_LENGTH_BINARY_EXPONENT,
            MAX_LENGTH_BINARY_TOTAL
        )
        decimalDisplay.text = flavors.decimalString
        binaryToDecimalFormulaGraphic.drawSubtractLabel(Binary.binaryStringToInt(flavors.binaryString.substring(
            MAX_LENGTH_BINARY_SIGN,
            MAX_LENGTH_BINARY_SIGN + MAX_LENGTH_BINARY_EXPONENT
        )))
    }

    private fun updateDisplaysAndRegister(flavors: FlavorsOfFloat) {
        updateDisplays(flavors)
        if (isObserving) updateAnyAttachedRegister(flavors.intValue)
    }

    private fun updateSignificandLabel(flavors: FlavorsOfFloat) {
        // Will change significandLabel text only if it needs to be changed.
        val left = flavors.binaryString.substring(MAX_LENGTH_BINARY_SIGN, MAX_LENGTH_BINARY_SIGN + MAX_LENGTH_BINARY_EXPONENT)
        val right = ZEROES.substring(MAX_LENGTH_BINARY_SIGN, MAX_LENGTH_BINARY_SIGN + MAX_LENGTH_BINARY_EXPONENT)
        if (left == right) {
            if (!significandLabel.text.contains("deno"))
                significandLabel.text = DENORMALIZED_LABEL
        } else {
            if (!significandLabel.text.contains("unde"))
                significandLabel.text = NORMALIZED_LABEL
        }
    }

    /**
     * Encapsulates 5 different representations of a 32-bit floating point value:
     *   - String with hex value
     *   - String with binary value, 32 characters long
     *   - String with decimal float value, variable length
     *   - Int with 32-bit representation of float value ("int bits")
     *   - String for display only, showing formula for expanding bits to decimal
     */
    private inner class FlavorsOfFloat {
        var hexString: String = DEFAULT_HEX
        var binaryString: String = DEFAULT_BINARY_SIGN + DEFAULT_BINARY_EXPONENT + DEFAULT_BINARY_FRACTION
        var decimalString: String = DEFAULT_DECIMAL
        var expansionString: String = buildExpansionFromBinaryString(binaryString)
        var intValue: Int = decimalString.toFloat().toIntBits()

        fun fromHexString(hexString: String): FlavorsOfFloat {
            this.hexString = "0x${addLeadingZeroes(
                (if ((hexString.indexOf("0X") == 0 || hexString.indexOf("0x") == 0)) hexString.substring(2) else hexString),
                MAX_LENGTH_HEX
            )}"
            this.binaryString = Binary.hexStringToBinaryString(this.hexString)
            this.decimalString = Binary.binaryStringToInt(this.binaryString).bitsToFloat().toString()
            this.expansionString = buildExpansionFromBinaryString(this.binaryString)
            this.intValue = Binary.binaryStringToInt(this.binaryString)
            return this
        }

        @Deprecated(
            "Renamed to fromHexString.",
            ReplaceWith("fromHexString(hexString)"),
            DeprecationLevel.ERROR
        )
        fun buildOneFromHexString(hexString: String) = fromHexString(hexString)

        fun fromBinaryString(): FlavorsOfFloat {
            this.binaryString = getFullBinaryStringFromDisplays()
            this.hexString = Binary.binaryStringToHexString(this.binaryString)
            this.decimalString = Binary.binaryStringToInt(this.binaryString).bitsToFloat().toString()
            this.expansionString = buildExpansionFromBinaryString(this.binaryString)
            this.intValue = Binary.binaryStringToInt(this.binaryString)
            return this
        }

        @Deprecated(
            "Renamed to fromBinaryString.",
            ReplaceWith("fromBinaryString()"),
            DeprecationLevel.ERROR
        )
        fun buildOneFromBinaryString() = fromBinaryString()

        fun fromDecimalString(decimalString: String): FlavorsOfFloat? {
            val floatValue = decimalString.toFloatOrNull() ?: return null
            this.decimalString = floatValue.toString()
            this.intValue = floatValue.toIntBits()
            this.binaryString = Binary.intToBinaryString(this.intValue)
            this.hexString = Binary.binaryStringToHexString(this.binaryString)
            this.expansionString = buildExpansionFromBinaryString(this.binaryString)
            return this
        }

        @Deprecated(
            "Renamed to fromDecimalString.",
            ReplaceWith("fromDecimalString(decimalString)"),
            DeprecationLevel.ERROR
        )
        fun buildOneFromDecimalString(decimalString: String) = fromDecimalString(decimalString)

        fun fromInt(intValue: Int): FlavorsOfFloat {
            this.intValue = intValue
            this.binaryString = Binary.intToBinaryString(intValue)
            this.hexString = Binary.binaryStringToHexString(binaryString)
            this.decimalString = Binary.binaryStringToInt(this.binaryString).bitsToFloat().toString()
            this.expansionString = buildExpansionFromBinaryString(this.binaryString)
            return this
        }

        @Deprecated(
            "Renamed to fromInt.",
            ReplaceWith("fromInt(intValue)"),
            DeprecationLevel.ERROR
        )
        fun buildOneFromInt(intValue: Int) = fromInt(intValue)

        fun buildExpansionFromBinaryString(binaryString: String): String {
            val biasedExponent = Binary.binaryStringToInt(binaryString.substring(MAX_LENGTH_BINARY_SIGN, MAX_LENGTH_BINARY_SIGN + MAX_LENGTH_BINARY_EXPONENT))
            val stringExponent = (biasedExponent - EXPONENT_BIAS).toString()
            // Break all the in-string calculated components out to make the line easier to read
            val one = binaryString[0]
            val two = HTML_SPACES.substring(0, (5 - stringExponent.length) * 6)
            val three = if (biasedExponent == 0) "&nbsp;." else "<u>1</u>."
            val four = binaryString.substring(MAX_LENGTH_BINARY_SIGN + MAX_LENGTH_BINARY_EXPONENT, MAX_LENGTH_BINARY_TOTAL)
            return "<html><head></head><body>$EXPANSION_FONT_TAG-1<sup>$one</sup> &nbsp;*&nbsp; 2<sup>$stringExponent$two</sup> &nbsp;* &nbsp;$three$four =</font></body></html>"
        }

        private fun getFullBinaryStringFromDisplays(): String =
            addLeadingZeroes(binarySignDisplay.text, MAX_LENGTH_BINARY_SIGN) +
            addLeadingZeroes(binaryExponentDisplay.text, MAX_LENGTH_BINARY_EXPONENT) +
            addLeadingZeroes(binaryFractionDisplay.text, MAX_LENGTH_BINARY_FRACTION)

        private fun addLeadingZeroes(str: String, length: Int): String =
            if (str.length < length) ZEROES.substring(0, min(ZEROES.length, length - str.length)) + str else str
    }

    /** Handle input keystrokes for the hex field. */
    private inner class HexDisplayKeystrokeListener(private val digitLength: Int) : KeyAdapter() {
        override fun keyTyped(e: KeyEvent) {
            val source = e.component as JTextField
            if (e.keyChar.code == KeyEvent.VK_BACK_SPACE || e.keyChar.code == KeyEvent.VK_TAB) return
            if (!isHexDigit(e.keyChar) || source.text.length == digitLength && source.selectedText == null) {
                if (e.keyChar.code != KeyEvent.VK_ENTER && e.keyChar.code != KeyEvent.VK_TAB) {
                    Toolkit.getDefaultToolkit().beep()
                    instructions.text = if (source.text.length == digitLength && source.selectedText == null) {
                        "The maximum length of this field is $digitLength."
                    } else {
                        "Only digits and letters A to F (case-insensitive) are accepted in the hexadecimal field."
                    }
                }
                e.consume()
            }
        }

        override fun keyPressed(e: KeyEvent) {
            if (e.keyChar.code == KeyEvent.VK_ENTER || e.keyChar.code == KeyEvent.VK_TAB) {
                updateDisplaysAndRegister(FlavorsOfFloat().fromHexString((e.source as JTextField).text))
                instructions.text = DEFAULT_INSTRUCTIONS
                e.consume()
            }
        }

        private fun isHexDigit(digit: Char): Boolean =
            digit in listOf(
                '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                'a', 'b', 'c', 'd', 'e', 'f',
                'A', 'B', 'C', 'D', 'E', 'F'
            )
    }

    /** Handle input keystrokes for the binary field. */
    private inner class BinaryDisplayKeystrokeListener(private val bitLength: Int) : KeyAdapter() {
        override fun keyTyped(e: KeyEvent) {
            val source = e.component as JTextField
            if (e.keyChar.code == KeyEvent.VK_BACK_SPACE) return
            if (!isBinaryDigit(e.keyChar) || e.keyChar.code == KeyEvent.VK_ENTER || source.text.length == bitLength && source.selectedText == null) {
                if (e.keyChar.code != KeyEvent.VK_ENTER) {
                    Toolkit.getDefaultToolkit().beep()
                    instructions.text = if (source.text.length == bitLength && source.selectedText == null) {
                        "The maximum length of this field is $bitLength."
                    } else {
                        "Only 0 and 1 are accepted in the binary field."
                    }
                }
                e.consume()
            }
        }

        override fun keyPressed(e: KeyEvent) {
            if (e.keyChar.code == KeyEvent.VK_ENTER) {
                updateDisplaysAndRegister(FlavorsOfFloat().fromBinaryString())
                instructions.text = DEFAULT_INSTRUCTIONS
                e.consume()
            }
        }

        private fun isBinaryDigit(digit: Char): Boolean = digit == '0' || digit == '1'
    }

    /** Handle input keystrokes for the decimal field. */
    private inner class DecimalDisplayKeystrokeListener : KeyAdapter() {
        override fun keyTyped(e: KeyEvent) {
            if (e.keyChar.code == KeyEvent.VK_BACK_SPACE) return
            if (!isDecimalFloatDigit(e.keyChar)) {
                if (e.keyChar.code != KeyEvent.VK_ENTER) {
                    instructions.text =
                        "Only digits, decimal points, signs, and E (or e) are accepted in the decimal field."
                    Toolkit.getDefaultToolkit().beep()
                }
                e.consume()
            }
        }

        override fun keyPressed(e: KeyEvent) {
            if (e.keyChar.code == KeyEvent.VK_ENTER) {
                val text = (e.source as JTextField).text
                FlavorsOfFloat().fromDecimalString(text)?.let {
                    updateDisplaysAndRegister(it)
                    instructions.text = DEFAULT_INSTRUCTIONS
                } ?: run {
                    Toolkit.getDefaultToolkit().beep()
                    instructions.text = "'$text' is not a valid floating-point number."
                }
                e.consume()
            }
        }

        private fun isDecimalFloatDigit(digit: Char): Boolean =
            digit in listOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '+', '.', 'e', 'E')
    }

    /** Draw visuals relating the hex values to the decimal values. */
    private inner class HexToBinaryGraphicPanel : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.color = Color.red
            val upperY = 0
            val lowerY = 60
            // Assume all 3 binary displays use the same geometry, so column width is the same for all.
            val hexColumnWidth = hexDisplay.width / hexDisplay.columns
            val binaryColumnWidth = binaryFractionDisplay.width / binaryFractionDisplay.columns
            // Loop will handle the lower order 5 "nibbles" (hex digits)
            for (i in 1..<6) g.fillPolygon(
                hexDisplay.x + hexColumnWidth * (hexDisplay.columns - i) + hexColumnWidth / 2 to upperY,
                binaryFractionDecoratedDisplay.x + binaryColumnWidth * (binaryFractionDisplay.columns - ((i * 5) - i)) to lowerY,
                binaryFractionDecoratedDisplay.x + binaryColumnWidth * (binaryFractionDisplay.columns - (((i * 5) - i) - 4)) to lowerY
            )
            // Nibble 5 straddles the binary display of the exponent and fraction.
            g.fillPolygon(
                hexDisplay.x + hexColumnWidth * (hexDisplay.columns - 6) + hexColumnWidth / 2 to upperY,
                binaryFractionDecoratedDisplay.x + binaryColumnWidth * (binaryFractionDisplay.columns - 20) to lowerY,
                binaryExponentDecoratedDisplay.x + binaryColumnWidth * (binaryExponentDisplay.columns - 1) to lowerY
            )
            // Nibble 6 maps to binary display of exponent.
            g.fillPolygon(
                hexDisplay.x + hexColumnWidth * (hexDisplay.columns - 7) + hexColumnWidth / 2 to upperY,
                binaryExponentDecoratedDisplay.x + binaryColumnWidth * (binaryExponentDisplay.columns - 1) to lowerY,
                binaryExponentDecoratedDisplay.x + binaryColumnWidth * (binaryExponentDisplay.columns - 5) to lowerY
            )
            // Nibble 7 straddles the binary display of sign and exponent.
            g.fillPolygon(
                hexDisplay.x + hexColumnWidth * (hexDisplay.columns - 8) + hexColumnWidth / 2 to upperY,
                binaryExponentDecoratedDisplay.x + binaryColumnWidth * (binaryExponentDisplay.columns - 5) to lowerY,
                binarySignDecoratedDisplay.x to lowerY
            )
        }
    }

    /**
     * Panel to hold arrows explaining transformation of binary representation into formula for calculating decimal
     * value.
     */
    private inner class BinaryToDecimalFormulaGraphic : JPanel() {
        val subtractLabelTrailer = " - 127"
        val arrowHeadOffset = 5
        val lowerY = 0
        val upperY = 50
        var centerX: Int = -1
        var exponentCenterX: Int = -1
        var subtractLabelWidth: Int = -1
        var subtractLabelHeight: Int = -1
        val centerY = (upperY - lowerY) / 2
        val upperYArrowHead = upperY - arrowHeadOffset
        var currentExponent = Binary.binaryStringToInt(DEFAULT_BINARY_EXPONENT)

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            // Arrow down from the binary sign field.
            centerX = binarySignDecoratedDisplay.x + binarySignDecoratedDisplay.width / 2
            g.drawLine(centerX, lowerY, centerX, upperY)
            g.drawLine(centerX - arrowHeadOffset, upperYArrowHead, centerX, upperY)
            g.drawLine(centerX + arrowHeadOffset, upperYArrowHead, centerX, upperY)
            // Arrow down from the binary exponent field.
            centerX = binaryExponentDecoratedDisplay.x + binaryExponentDecoratedDisplay.width / 2
            g.drawLine(centerX, lowerY, centerX, upperY)
            g.drawLine(centerX - arrowHeadOffset, upperYArrowHead, centerX, upperY)
            g.drawLine(centerX + arrowHeadOffset, upperYArrowHead, centerX, upperY)
            // Label on exponent arrow. The two assignments serve to initialize two
            // instance variables that are used by drawSubtractLabel(). They are
            // initialized here because they cannot be initialized sooner, and because
            // the drawSubtractLabel() method will later be called by updateDisplays(),
            // an outsider which has no other access to that information. Once set, they
            // do not change, so it does no harm that they are "re-initialized" each time
            // this method is called (which occurs only upon startup and when this portion
            // of the GUI needs to be repainted).
            exponentCenterX = centerX
            subtractLabelHeight = g.fontMetrics.height
            drawSubtractLabel(g, buildSubtractLabel(currentExponent))
            // Arrow down from the binary fraction field.
            centerX = binaryFractionDecoratedDisplay.x + binaryFractionDecoratedDisplay.width / 2
            g.drawLine(centerX, lowerY, centerX, upperY)
            g.drawLine(centerX - arrowHeadOffset, upperYArrowHead, centerX, upperY)
            g.drawLine(centerX - arrowHeadOffset, upperYArrowHead, centerX, upperY)
        }

        // Update the display of the exponent and bias without passing a Graphics instance.
        fun drawSubtractLabel(exponent: Int) {
            if (exponent != currentExponent) {
                currentExponent = exponent
                drawSubtractLabel(graphics, buildSubtractLabel(exponent))
            }
        }

        private fun drawSubtractLabel(g: Graphics, label: String) {
            // Clear the existing subtract label.  The "+2" overwrites the arrow at initial paint when label width is 0.
            // Originally used "clearRect()" but changed to "fillRect()" with background color, because when running
            // as a MarsTool it would clear with a different color.
            val saved = g.color
            g.color = binaryToDecimalFormulaGraphic.background
            g.fillRect(
                exponentCenterX - subtractLabelWidth / 2,
                centerY - subtractLabelHeight / 2, subtractLabelWidth + 2,
                subtractLabelHeight
            )
            g.color = saved
            subtractLabelWidth = g.fontMetrics.stringWidth(label)
            g.drawString(label, exponentCenterX - subtractLabelWidth / 2, centerY + subtractLabelHeight / 2 - 3)
        }

        private fun buildSubtractLabel(value: Int) = "$value $subtractLabelTrailer"
    }

    /**
     * Class to allow the client to set text without needing to know how/whether the text needs to be formatted.
     * Used to display instructions.
     */
    private inner class InstructionsPane(parent: Component) : JLabel(DEFAULT_INSTRUCTIONS) {
        init {
            font = instructionFont
            background = parent.background
        }
    }

    /**
     * Used to draw custom background in binary fraction display.
     */
    private class BinaryFractionDisplayTextField(value: String, columns: Int) : JTextField(value, columns) {
        override fun paintComponent(g: Graphics) = super.paintComponent(g)
    }
}