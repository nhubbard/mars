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

@file:Suppress("DEPRECATION", "ControlFlowWithEmptyBody")

package edu.missouristate.mars.tools

import edu.missouristate.mars.Globals
import edu.missouristate.mars.mips.hardware.AccessNotice
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.toRadians
import java.awt.*
import java.util.*
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.math.cos
import kotlin.math.sin

class MarsBot : Observer, MarsTool {
    companion object {
        private const val GRAPHIC_WIDTH = 512
        private const val GRAPHIC_HEIGHT = 512
        private const val HEADING_ADDR: Int = 0xffff8010.toInt()
        private const val LEAVE_TRACK_ADDR: Int = 0xffff8020.toInt()
        private const val X_ADDR: Int = 0xffff8030.toInt()
        private const val Y_ADDR: Int = 0xffff8040.toInt()
        private const val MOVE_ADDR: Int = 0xffff8050.toInt()
    }

    private lateinit var graphicArea: MarsBotDisplay
    // 0 is north, 90 is east, etc.
    private var heading: Int = 0
    private var leaveTrack: Boolean = false
    private var xPos = 0.0
    private var yPos = 0.0
    private var isMoving = false

    private val trackPts = 256
    private val arrayOfTrack = Array(trackPts) { Point(0, 0) }
    private var trackIndex = 0

    override val toolName: String = "MarsBot"

    override fun action() {
        val br1 = BotRunnable()
        val t1 = Thread(br1)
        t1.start()
        try {
            Globals.memory.addObserver(this, 0xffff8000.toInt(), 0xffff8060.toInt())
        } catch (aee: AddressErrorException) {
            println(aee)
        }
    }

    override fun update(o: Observable, arg: Any?) {
        if (arg !is MemoryAccessNotice) return
        val address = arg.address
        if (address < 0 && arg.accessType == AccessNotice.AccessType.WRITE) {
            if (address == HEADING_ADDR) {
                heading = arg.value
            } else if (address == LEAVE_TRACK_ADDR) {
                // If we HAD NOT been leaving a track, but we should NOW leave
                // a track, put start point into the array.
                if (!leaveTrack && arg.value == 1) {
                    leaveTrack = true
                    arrayOfTrack[trackIndex] = Point(xPos.toInt(), yPos.toInt())
                    trackIndex++ // the index of the end point
                } else if (!leaveTrack && arg.value == 0) {
                    // NO ACTION
                } else if (leaveTrack && arg.value == 1) {
                    // NO ACTION
                } else if (leaveTrack && arg.value == 0) {
                    leaveTrack = false
                    arrayOfTrack[trackIndex] = Point(xPos.toInt(), yPos.toInt())
                    trackIndex++ // the index of the next start point
                }
            } else if (address == MOVE_ADDR) {
                isMoving = arg.value != 0
            } else if (address == X_ADDR || address == Y_ADDR) {
                // Ignore this memory write, because the write action originated within
                // this tool. This tool is being notified of the write action in the usual
                // manner, but the write action is already known to this tool.
                // NO ACTION
            }
        }
    }

    private inner class BotRunnable : Runnable {
        val panel: JPanel

        init {
            val frame = JFrame("Bot")
            panel = JPanel(BorderLayout())
            graphicArea = MarsBotDisplay()
            val buttonPanel = JPanel()

            val clearButton = JButton("Clear")
            clearButton.addActionListener {
                graphicArea.clear()
                leaveTrack = false
                xPos = 0.0
                yPos = 0.0
                isMoving = false
                trackIndex = 0
            }
            buttonPanel.add(clearButton)

            val closeButton = JButton("Close")
            closeButton.addActionListener { frame.isVisible = false }
            buttonPanel.add(closeButton)

            panel.add(graphicArea, BorderLayout.CENTER)
            panel.add(buttonPanel, BorderLayout.SOUTH)

            frame.contentPane.add(panel)
            frame.pack()
            frame.isVisible = true
            frame.title = " This is the MarsBot"
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.size = Dimension(GRAPHIC_WIDTH + 200, GRAPHIC_HEIGHT + 100)
            frame.isVisible = true
        }

        override fun run() {
            var tempAngle: Double
            do {
                if (isMoving) {
                    tempAngle = ((360.0 - heading) + 90.0) % 360.0
                    xPos += cos(tempAngle.toRadians())
                    yPos -= sin(tempAngle.toRadians())

                    try {
                        Globals.memory.setWord(X_ADDR, xPos.toInt())
                        Globals.memory.setWord(Y_ADDR, yPos.toInt())
                    } catch (ignored: AddressErrorException) {}

                    arrayOfTrack[trackIndex] = Point(xPos.toInt(), yPos.toInt())
                } else {
                    // Not moving
                }
                try {
                    Thread.sleep(40)
                } catch (ignored: InterruptedException) {}
                panel.repaint()
            } while (true)
        }
    }

    private inner class MarsBotDisplay : JPanel() {
        fun redraw() = repaint()
        fun clear() = repaint()

        override fun paintComponent(g: Graphics) {
            g as Graphics2D
            g.color = Color.blue
            for (i in 1..trackIndex step 2) {
                try {
                    g.drawLine(arrayOfTrack[i - 1].x, arrayOfTrack[i - 1].y, arrayOfTrack[i].x, arrayOfTrack[i].y)
                } catch (ignored: Exception) {}
            }
            g.color = Color.black
            g.fillRect(xPos.toInt(), yPos.toInt(), 20, 20)
        }
    }
}