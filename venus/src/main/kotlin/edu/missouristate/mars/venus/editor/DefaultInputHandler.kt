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

@file:Suppress("UNCHECKED_CAST")

package edu.missouristate.mars.venus.editor

import edu.missouristate.mars.UIGlobals
import edu.missouristate.mars.hashTableOf
import java.awt.Toolkit
import java.awt.event.ActionListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.util.*
import javax.swing.KeyStroke

/**
 * The default input handler. It maps sequences of keystrokes into actions and inserts key typed events into the text
 * area.
 */
class DefaultInputHandler : InputHandler {
    companion object {
        /**
         * Converts a string to a keystroke. The string should be of the
         * form *modifiers*+*shortcut* where *modifiers*
         * is any combination of A for Alt, C for Control, S for Shift
         * or M for Meta, and *shortcut* is either a single character,
         * or a keycode name from the [KeyEvent] class, without
         * the `VK_` prefix.
         *
         * @param keyStroke A string description of the keystroke
         */
        @JvmStatic
        fun parseKeyStroke(keyStroke: String?): KeyStroke? {
            keyStroke ?: return null
            var modifiers = 0
            val index = keyStroke.indexOf("+")
            if (index != -1) {
                for (i in 0..<index) {
                    modifiers = when (keyStroke[i].uppercaseChar()) {
                        'A' -> modifiers or InputEvent.ALT_DOWN_MASK
                        'C' -> modifiers or InputEvent.CTRL_DOWN_MASK
                        'M' -> modifiers or InputEvent.META_DOWN_MASK
                        'S' -> modifiers or InputEvent.SHIFT_DOWN_MASK
                        else -> modifiers
                    }
                }
            }
            val key = keyStroke.substring(index + 1)
            if (key.length == 1) {
                val ch = key[0].uppercaseChar()
                return if (modifiers == 0) KeyStroke.getKeyStroke(ch)
                else KeyStroke.getKeyStroke(ch, modifiers)
            } else if (key.isEmpty()) {
                System.err.println("Invalid keystroke: $keyStroke")
                return null
            } else {
                // TODO: Make this more... static? I don't like using reflection for this.
                // TODO: It's too... brittle.
                val ch = try {
                    KeyEvent::class.java.getField("VK_$key").getInt(null)
                } catch (e: Exception) {
                    System.err.println("Invalid keystroke: $keyStroke")
                    return null
                }
                return KeyStroke.getKeyStroke(ch, modifiers)
            }
        }
    }

    private var bindings: Hashtable<KeyStroke, Any>
    private var currentBindings: Hashtable<KeyStroke, Any>

    /** Create a new input handler with no key bindings defined. */
    constructor() {
        bindings = hashTableOf()
        currentBindings = hashTableOf()
    }

    /** Set up the default key bindings. */
    private constructor(copy: DefaultInputHandler) {
        currentBindings = copy.bindings
        bindings = currentBindings
    }

    override fun addDefaultKeyBindings() {
        listOf(
            "BACK_SPACE" to BACKSPACE,
            "C+BACK_SPACE" to BACKSPACE_WORD,
            "DELETE" to DELETE,
            "C+DELETE" to DELETE_WORD,
            "ENTER" to INSERT_BREAK,
            "TAB" to INSERT_TAB,
            "INSERT" to OVERWRITE,
            "C+\\" to TOGGLE_RECT,
            "HOME" to HOME,
            "END" to END,
            "C+A" to SELECT_ALL,
            "S+HOME" to SELECT_HOME,
            "S+END" to SELECT_END,
            "C+HOME" to DOCUMENT_HOME,
            "C+END" to DOCUMENT_END,
            "CS+HOME" to SELECT_DOC_HOME,
            "CS+END" to SELECT_DOC_END,
            "PAGE_UP" to PREV_PAGE,
            "PAGE_DOWN" to NEXT_PAGE,
            "S+PAGE_UP" to SELECT_PREV_PAGE,
            "S+PAGE_DOWN" to SELECT_NEXT_PAGE,
            "LEFT" to PREV_CHAR,
            "S+LEFT" to SELECT_PREV_CHAR,
            "C+LEFT" to PREV_WORD,
            "CS+LEFT" to SELECT_PREV_WORD,
            "RIGHT" to NEXT_CHAR,
            "S+RIGHT" to SELECT_NEXT_CHAR,
            "C+RIGHT" to NEXT_WORD,
            "CS+RIGHT" to SELECT_NEXT_WORD,
            "UP" to PREV_LINE,
            "S+UP" to SELECT_PREV_LINE,
            "DOWN" to NEXT_LINE,
            "S+DOWN" to SELECT_NEXT_LINE,
            "C+ENTER" to REPEAT,
            "C+C" to CLIP_COPY,
            "C+V" to CLIP_PASTE,
            "C+X" to CLIP_CUT
        ).forEach { (accel, action) -> addKeyBinding(accel, action) }
    }

    /**
     * Adds a key binding to this input handler. The key binding is
     * a list of whitespace-separated keystrokes of the form
     * <i>[modifiers+]key</i> where modifier is C for Control, A for Alt,
     * or S for Shift, and key is either a character (a-z) or a field
     * name in the KeyEvent class prefixed with VK_ (e.g., BACK_SPACE)
     *
     * @param keyBinding The key binding
     * @param action     The action
     */
    // TODO: Whoever initially wrote this code, I hate you.
    //       I know you couldn't have predicted that Java 5 would add generics.
    //       But this is still an abomination.
    //       Why shove both `Any` and `Hashtable<KeyStroke, Any>` into the same type argument?
    //       Eventually, I will have to fix this with some kind of data structure, but still...
    //       This is a *terrible* hack that makes debugging and maintaining this codebase a nightmare.
    override fun addKeyBinding(keyBinding: String, action: ActionListener) {
        var current = bindings
        val st = StringTokenizer(keyBinding)
        while (st.hasMoreTokens()) {
            val keyStroke = parseKeyStroke(st.nextToken()) ?: return
            if (st.hasMoreTokens()) {
                var o = current[keyStroke]
                if (o !is Hashtable<*, *>) {
                    o = hashTableOf<KeyStroke, Any>()
                    current[keyStroke] = o
                }
                current = o as Hashtable<KeyStroke, Any>
            } else current[keyStroke] = action
        }
    }

    override fun removeKeyBinding(keyBinding: String) {
        // Variation from original codebase: added implementation, because this is an obvious improvement.
        bindings.remove(KeyStroke.getKeyStroke(keyBinding))
    }

    override fun removeAllKeyBindings() {
        bindings.clear()
    }

    override fun copy(): InputHandler = DefaultInputHandler(this)

    override fun keyPressed(e: KeyEvent) {
        val keyCode = e.keyCode
        val modifiers = e.modifiersEx
        if (keyCode in listOf(VK_CONTROL, VK_SHIFT, VK_ALT, VK_META)) return
        if ((modifiers and SHIFT_DOWN_MASK.inv()) != 0 || e.isActionKey ||
            keyCode in listOf(VK_BACK_SPACE, VK_DELETE, VK_ENTER, VK_TAB, VK_ESCAPE)) {
            if (grabAction != null) {
                handleGrabAction(e)
                return
            }
            val keyStroke = KeyStroke.getKeyStroke(keyCode, modifiers)
            when (val o = currentBindings[keyStroke]) {
                null -> {
                    // This is a key we don't know about unless a prefix is active.
                    // Without this branch, MARS will beep when keys like Caps Lock are pressed.
                    if (currentBindings != bindings) {
                        Toolkit.getDefaultToolkit().beep()
                        // F10 should be passed on, but C+e F10 should not
                        repeatCount = 0
                        isRepeatEnabled = false
                        e.consume()
                    }
                    currentBindings = bindings
                    // No binding for this keystroke; pass it to the menu to check accelerators.
                    UIGlobals.gui.dispatchEventToMenu(e)
                    // Don't beep if the user presses this key.
                    e.consume()
                }
                is ActionListener -> {
                    currentBindings = bindings
                    executeAction(o, e.source, null)
                    e.consume()
                }
                is Hashtable<*, *> -> {
                    currentBindings = o as Hashtable<KeyStroke, Any>
                    e.consume()
                }
            }
        }
    }

    override fun keyTyped(e: KeyEvent) {
        val modifiers = e.modifiersEx
        val c = e.keyChar
        // Prevent Mac shortcut from being echoed into the text area.
        // Example: Command-S will echo the 's' character into the text area unless filtered out here.
        // The Command modifier matches KeyEvent.META_DOWN_MASK.
        if ((modifiers and META_DOWN_MASK) != 0) return
        // Handle Italian Mac keyboards, which require using Alt to insert the pound symbol (#) for comments.
        if (c != CHAR_UNDEFINED && (((modifiers and ALT_DOWN_MASK) == 0) || System.getProperty("os.name").contains("OS X"))) {
            if (c >= 0x20.digitToChar() && c != 0x7f.digitToChar()) {
                val keyStroke = KeyStroke.getKeyStroke(c.uppercaseChar())
                val o = currentBindings[keyStroke]
                if (o is Hashtable<*, *>) {
                    currentBindings = o as Hashtable<KeyStroke, Any>
                    return
                } else if (o is ActionListener) {
                    currentBindings = bindings
                    executeAction(o, e.source, c.toString())
                    return
                }
                currentBindings = bindings
                if (grabAction != null) {
                    handleGrabAction(e)
                    return
                }
                // 0-9 adds another 'digit' to the repeat number
                if (isRepeatEnabled && c.isDigit()) {
                    repeatCount *= 10
                    repeatCount += c - '0'
                    return
                }
                executeAction(INSERT_CHAR, e.source, e.keyChar.toString())
                repeatCount = 0
                isRepeatEnabled = false
            }
        }
    }
}