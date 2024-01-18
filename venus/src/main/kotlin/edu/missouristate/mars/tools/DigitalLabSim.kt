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

@file:Suppress("DEPRECATION", "SameParameterValue")

package edu.missouristate.mars.tools

import edu.missouristate.mars.Globals
import edu.missouristate.mars.UIGlobals
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor0
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.simulator.Simulator
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.util.*
import javax.swing.*
import javax.swing.JOptionPane.INFORMATION_MESSAGE
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

class DigitalLabSim @JvmOverloads constructor(
    title: String = "$HEADING, $VERSION",
    heading: String = HEADING
) : AbstractMarsToolAndApplication(title, heading) {
    companion object {
        private const val HEADING = "Digital Lab Sim"
        private const val VERSION = "Version 1.0 (Didier Teifreto)"
        private const val COUNTER_VALUE_MAX = 30

        @JvmStatic private val IN_ADDRESS_DISPLAY_1 = Memory.memoryMapBaseAddress + 0x10
        @JvmStatic private val IN_ADDRESS_DISPLAY_2 = Memory.memoryMapBaseAddress + 0x11
        @JvmStatic private val IN_ADDRESS_HEX_KEYBOARD = Memory.memoryMapBaseAddress + 0x12
        @JvmStatic private val IN_ADDRESS_COUNTER = Memory.memoryMapBaseAddress + 0x13
        @JvmStatic private val OUT_ADDRESS_HEX_KEYBOARD = Memory.memoryMapBaseAddress + 0x14

        const val EXTERNAL_INTERRUPT_TIMER = 0x00000100
        const val EXTERNAL_INTERRUPT_HEX_KEYBOARD = 0x00000200

        // -1 is no button click
        @JvmStatic private var keyboardValueButtonClick = -1
        @JvmStatic private var keyboardInterruptOnOff = false
        @JvmStatic private var counterValue = COUNTER_VALUE_MAX
        @JvmStatic private var counterInterruptOnOff = false
        @JvmStatic private lateinit var secondCounter: OneSecondCounter

        @JvmStatic
        fun main(args: Array<String>) {
            DigitalLabSim("$HEADING, $VERSION", HEADING).go()
        }
    }

    private lateinit var sevenSegmentPanel: SevenSegmentPanel
    private lateinit var hexKeyboardPanel: HexKeyboard

    override val toolName: String = HEADING

    override fun addAsObserver() {
        addAsObserver(IN_ADDRESS_DISPLAY_1, IN_ADDRESS_DISPLAY_1)
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress)
    }

    override fun update(resource: Observable, accessNotice: Any) {
        if (accessNotice !is MemoryAccessNotice) return
        val address = accessNotice.address
        val value = accessNotice.value.digitToChar()
        when (address) {
            IN_ADDRESS_DISPLAY_1 -> updateSevenSegment(1, value)
            IN_ADDRESS_DISPLAY_2 -> updateSevenSegment(0, value)
            IN_ADDRESS_HEX_KEYBOARD -> updateHexKeyboard(value)
            IN_ADDRESS_COUNTER -> updateOneSecondCounter(value)
        }
        if (counterInterruptOnOff) {
            if (counterValue > 0) counterValue--
        } else {
            counterValue = COUNTER_VALUE_MAX
            if ((Coprocessor0.getValue(Coprocessor0.STATUS) and 2) == 0)
                Simulator.externalInterruptingDevice = Exceptions.EXTERNAL_INTERRUPT_TIMER
        }
    }

    override fun reset() {
        sevenSegmentPanel.reset()
        hexKeyboardPanel.reset()
        secondCounter.reset()
    }

    override fun buildMainDisplayArea(): JComponent {
        val panelTools = JPanel(GridLayout(1, 2))
        sevenSegmentPanel = SevenSegmentPanel()
        panelTools.add(sevenSegmentPanel)
        hexKeyboardPanel = HexKeyboard()
        panelTools.add(hexKeyboardPanel)
        secondCounter = OneSecondCounter()
        return panelTools
    }

    @Synchronized
    private fun updateMMIOControlAndData(dataAddr: Int, dataValue: Int) {
        if (isBeingUsedAsAMarsTool || connectButton?.isConnected == true) {
            Globals.memoryAndRegistersLock.withLock {
                try {
                    Globals.memory.setByte(dataAddr, dataValue)
                } catch (aee: AddressErrorException) {
                    println("Tool author specified incorrect MMIO address! $aee")
                    exitProcess(1)
                }
            }
            UIGlobals.gui.mainPane.executePane.let {
                if (it.textSegmentWindow.codeHighlighting) it.dataSegmentWindow.updateValues()
            }
        }
    }

    override fun getHelpComponent(): JComponent {
        val helpContent = """
        This tool is composed of 3 parts: two seven-segment displays, a hexadecimal keyboard, and a counter.
        
        Seven segment display:
          - Byte value at address 0xFFFF0010: command right seven segment display
          - Byte value at address 0xFFFF0011: command left seven segment display
          - Each bit of these two bytes are connected to segments (bit 0 for a segment, 1 for b segment and 7 for point)
        
        Hexadecimal keyboard:
          - Byte value at address 0xFFFF0012: command row number of hexadecimal keyboard (bit 0 to 3) and enable
            keyboard interrupt (bit 7)
          - Byte value at address 0xFFFF0014: receive row and column of the key pressed, 0 if not key pressed
          - The MIPS program has to scan, one by one, each row (send 1,2,4,8...) and observe if a key is pressed (that
            means the byte value at address 0xFFFF0014 is different from zero). This byte value is composed of a row
            number (4 left bits) and column number (4 right bits). Here you'll find the code for each key:
            0x11,0x21,0x41,0x81,0x12,0x22,0x42,0x82,0x14,0x24,0x44,0x84,0x18,0x28,0x48,0x88.
          - For example, key number 2 return 0x41, so the key is on column 3 and row 1.
          - If keyboard interrupts are enabled, an exception is thrown with cause register bit 11 set.
        
        Counter:
          - Byte value at address 0xFFFF0013: If one bit of this byte is set, the counter interruption is enabled.
          - If counter interruption is enabled, an exception is thrown every 30 instructions with cause register bit
            number 10 set.
        
        Contributed by Didier Teifreto (dteifreto@lifc.univ-fcomte.fr).
        """.trimIndent()
        val help = JButton("Help")
        help.addActionListener {
            val ja = JTextArea(helpContent)
            ja.rows = 25
            ja.columns = 120
            ja.lineWrap = true
            ja.wrapStyleWord = true
            JOptionPane.showMessageDialog(theWindow, JScrollPane(ja), "Digital Lab Simulator Help", INFORMATION_MESSAGE)
        }
        return help
    }

    private fun updateSevenSegment(number: Int, value: Char) {
        sevenSegmentPanel.display[number].modifyDisplay(value)
    }

    private fun updateHexKeyboard(row: Char) {
        val key = keyboardValueButtonClick
        if ((key != -1) && ((1 shl (key / 4)) == (row.digitToInt() and 0xF))) {
            updateMMIOControlAndData(OUT_ADDRESS_HEX_KEYBOARD, (1 shl (key / 4)) or (1 shl (4 + (key % 4))))
        } else {
            updateMMIOControlAndData(OUT_ADDRESS_HEX_KEYBOARD, 0)
        }
        keyboardInterruptOnOff = (row.digitToInt() and 0xF0) != 0
    }

    private fun updateOneSecondCounter(value: Char) {
        if (value != 0.digitToChar()) {
            counterInterruptOnOff = true
            counterValue = COUNTER_VALUE_MAX
        } else {
            counterInterruptOnOff = false
        }
    }

    private class SevenSegmentDisplay(var value: Char) : JComponent() {
        init {
            preferredSize = Dimension(60, 80)
        }

        fun modifyDisplay(value: Char) {
            this.value = value
            repaint()
        }

        fun switchSegment(g: Graphics, segment: Char) {
            when (segment) {
                'a' -> {
                    val pxa1 = intArrayOf(12, 9, 12)
                    val pxa2 = intArrayOf(36, 39, 36)
                    val pya = intArrayOf(5, 8, 11)
                    g.fillPolygon(pxa1, pya, 3)
                    g.fillPolygon(pxa2, pya, 3)
                    g.fillRect(12, 5, 24, 6)
                }
                'b' -> {
                    val pxb = intArrayOf(37, 40, 43)
                    val pyb1 = intArrayOf(12, 9, 12)
                    val pyb2 = intArrayOf(36, 39, 36)
                    g.fillPolygon(pxb, pyb1, 3)
                    g.fillPolygon(pxb, pyb2, 3)
                    g.fillRect(37, 12, 6, 24)
                }
                'c' -> {
                    val pxc = intArrayOf(37, 40, 43)
                    val pyc1 = intArrayOf(44, 41, 44)
                    val pyc2 = intArrayOf(68, 71, 68)
                    g.fillPolygon(pxc, pyc1, 3)
                    g.fillPolygon(pxc, pyc2, 3)
                    g.fillRect(37, 44, 6, 24)
                }
                'd' -> {
                    val pxd1 = intArrayOf(12, 9, 12)
                    val pxd2 = intArrayOf(36, 39, 36)
                    val pyd = intArrayOf(69, 72, 75)
                    g.fillPolygon(pxd1, pyd, 3)
                    g.fillPolygon(pxd2, pyd, 3)
                    g.fillRect(12, 69, 24, 6)
                }
                'e' -> {
                    val pxe = intArrayOf(5, 8, 11)
                    val pye1 = intArrayOf(44, 41, 44)
                    val pye2 = intArrayOf(68, 71, 68)
                    g.fillPolygon(pxe, pye1, 3)
                    g.fillPolygon(pxe, pye2, 3)
                    g.fillRect(5, 44, 6, 24)
                }
                'f' -> {
                    val pxf = intArrayOf(5, 8, 11)
                    val pyf1 = intArrayOf(12, 9, 12)
                    val pyf2 = intArrayOf(36, 39, 36)
                    g.fillPolygon(pxf, pyf1, 3)
                    g.fillPolygon(pxf, pyf2, 3)
                    g.fillRect(5, 12, 6, 24)
                }
                'g' -> {
                    val pxg1 = intArrayOf(12, 9, 12)
                    val pxg2 = intArrayOf(36, 39, 36)
                    val pyg = intArrayOf(37, 40, 43)
                    g.fillPolygon(pxg1, pyg, 3)
                    g.fillPolygon(pxg2, pyg, 3)
                    g.fillRect(12, 37, 24, 6)
                }
                'h' -> g.fillOval(49, 68, 8, 8)
            }
        }

        override fun paint(g: Graphics) {
            var c = 'a'
            while (c <= 'h') {
                g.color = if ((value.digitToInt() and 0x1) == 1) Color.RED else Color.LIGHT_GRAY
                switchSegment(g, c)
                value = (value.digitToInt() ushr 1).digitToChar()
                c++
            }
        }
    }

    private class SevenSegmentPanel : JPanel() {
        var display: Array<SevenSegmentDisplay>
            private set

        init {
            val fl = FlowLayout()
            layout = fl
            display = Array(2) {
                SevenSegmentDisplay(0.digitToChar())
            }
            add(display[0])
            add(display[1])
        }

        fun modifyDisplay(num: Int, value: Char) {
            display[num].modifyDisplay(value)
            display[num].repaint()
        }

        fun reset() {
            for (i in 0..<2) modifyDisplay(i, 0.digitToChar())
        }
    }

    private inner class HexKeyboard : JPanel() {
        var button: Array<JButton>
            private set

        init {
            layout = GridLayout(4, 4)
            button = Array(16) {
                JButton(it.toHexString()).apply {
                    background = Color.WHITE
                    margin = Insets(10, 10, 10, 10)
                    addMouseListener(ButtonClick(it))
                }
            }
            for (i in button.indices) add(button[i])
        }

        fun reset() {
            keyboardValueButtonClick = -1
            button.forEach { it.background = Color.WHITE }
        }

        inner class ButtonClick(private val buttonValue: Int) : MouseListener {
            override fun mouseClicked(e: MouseEvent) {
                if (keyboardValueButtonClick != -1) {
                    // Button already pressed, now release
                    keyboardValueButtonClick = -1
                    updateMMIOControlAndData(OUT_ADDRESS_HEX_KEYBOARD, 0)
                    button.forEach { it.background = Color.WHITE }
                } else {
                    // New button pressed
                    keyboardValueButtonClick = buttonValue
                    button[keyboardValueButtonClick].background = Color.WHITE
                    if (keyboardInterruptOnOff && (Coprocessor0.getValue(Coprocessor0.STATUS) and 2) == 0)
                        Simulator.externalInterruptingDevice = Exceptions.EXTERNAL_INTERRUPT_HEX_KEYBOARD
                }
            }

            override fun mousePressed(e: MouseEvent?) {}
            override fun mouseReleased(e: MouseEvent?) {}
            override fun mouseEntered(e: MouseEvent?) {}
            override fun mouseExited(e: MouseEvent?) {}
        }
    }

    private class OneSecondCounter {
        init {
            counterInterruptOnOff = false
        }

        fun reset() {
            counterInterruptOnOff = false
            counterValue = COUNTER_VALUE_MAX
        }
    }
}