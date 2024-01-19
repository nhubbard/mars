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

@file:Suppress("MemberVisibilityCanBePrivate")

package edu.missouristate.mars.venus

import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import javax.swing.Action
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.Timer

/**
 * This is a [JButton] which contains a timer
 * for firing events while the button is held down. There is a default
 * initial delay of 300ms before the first event is fired and a 60ms delay
 * between subsequent events. When the user holds the button down and moves
 * the mouse out from over the button, the timer stops, but if the user moves
 * the mouse back over the button, without having released the mouse button,
 * the timer starts up again at the same delay rate. If the enabled state is
 * changed while the timer is active, it will be stopped.
 *
 * NOTE: The normal button behavior is that the action event is fired after
 * the button is released. It may be important to know then that this is
 * still the case. So in effect, listeners will get one more event, then what
 * the internal timer fires. It's not a "bug", per se, just something to be
 * aware of. There seems to be no way to suppress the final event from
 * firing anyway, except to process all ActionListeners internally. But
 * realistically, it probably doesn't matter.
 */
open class RepeatButton : JButton, ActionListener, MouseListener {
    companion object {
        @JvmStatic private var testing = false

        @JvmStatic
        fun main(args: Array<String>) {
            testing = true
            val f = JFrame("RepeatButton Test")
            f.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

            val p = JPanel()
            val b = RepeatButton("Hold Me")
            b.actionCommand = "test"
            b.addActionListener(b)
            p.add(b)
            f.contentPane.add(p)
            f.pack()
            f.isVisible = true
        }
    }

    /** The pressed state for this button */
    private var pressed = false

    /** Flag to indicate that the button should fire events when held. If false, this button is just a JButton. */
    var isRepeatEnabled = true
        set(value) {
            if (!value) {
                pressed = false
                if (timer?.isRunning == true) timer?.stop()
            }
            field = value
        }

    /** The hold-down Timer for this button. */
    private var timer: Timer? = null

    /** The initial delay for this button. Hold-down time before the first event is fired. Milliseconds. */
    var initialDelay = 300

    /** The delay between firing events once the initial delay is passed. Milliseconds. */
    var delay = 60

    /**
     * Holds modifiers used when the mouse presses the button.
     * Used for subsequently fired action events.
     * May chance if the user mouses out, releases a key, and moves the mouse back in.
     */
    private var modifiers = 0

    constructor() : super() {
        init()
    }

    constructor(a: Action) : super(a) {
        init()
    }

    constructor(icon: Icon) : super(icon) {
        init()
    }

    constructor(text: String) : super(text) {
        init()
    }

    constructor(text: String, icon: Icon) : super(text, icon) {
        init()
    }

    private fun init() {
        addMouseListener(this)
        timer = Timer(delay, this)
        timer!!.isRepeats = true
    }

    override fun setEnabled(b: Boolean) {
        if (b != super.isEnabled()) {
            pressed = false
            if (timer?.isRunning == true) timer?.stop()
        }
        super.setEnabled(b)
    }

    override fun actionPerformed(e: ActionEvent) {
        if (e.source == timer) {
            val event = ActionEvent(this, ActionEvent.ACTION_PERFORMED, super.getActionCommand(), modifiers)
            super.fireActionPerformed(event)
        } else if (testing && e.source == this) println(e.actionCommand)
    }

    override fun mouseClicked(e: MouseEvent) {
        if (e.source == this) {
            pressed = false
            if (timer?.isRunning == true) timer?.stop()
        }
    }

    override fun mousePressed(e: MouseEvent) {
        if (e.source == this && isEnabled && isRepeatEnabled) {
            pressed = true
            if (timer?.isRunning == false) {
                modifiers = e.modifiersEx
                timer?.initialDelay = initialDelay
                timer?.start()
            }
        }
    }

    override fun mouseReleased(e: MouseEvent) {
        if (e.source == this) {
            pressed = false
            if (timer?.isRunning == true) timer?.stop()
        }
    }

    override fun mouseEntered(e: MouseEvent) {
        if (e.source == this && isEnabled && isRepeatEnabled) {
            if (pressed && timer?.isRunning == false) {
                modifiers = e.modifiersEx
                timer?.initialDelay = delay
                timer?.start()
            }
        }
    }

    override fun mouseExited(e: MouseEvent) {
        if (e.source == this && timer?.isRunning == true) timer?.stop()
    }
}