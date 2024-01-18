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

package edu.missouristate.mars.tools

import java.awt.Color
import java.util.ArrayList

class Vertex(
    var numIndex: Int,
    var init: Int,
    var end: Int,
    var name: String,
    var oppositeAxis: Int,
    var isMovingXAxis: Boolean,
    listOfColors: String,
    listTargetVertex: String,
    isText: Boolean
) {
    var current = 0
    var direction: Direction = Direction.NONE
        private set
    var color: Color
    var isFirstInteraction = false
    var isActive = false
    var isText = isText
        private set
    var targetVertex: ArrayList<Int>
        private set

    enum class Direction(val rawValue: Int) {
        NONE(0),
        UP(1),
        DOWN(2),
        LEFT(3),
        RIGHT(4);

        companion object {
            @JvmStatic
            fun fromInt(rawValue: Int) = entries.firstOrNull { it.rawValue == rawValue } ?: NONE
        }
    }

    init {
        current = init
        isFirstInteraction = true
        isActive = false
        color = Color(0, 153, 0)
        direction = if (isMovingXAxis) {
            if (init < end) Direction.LEFT
            else Direction.RIGHT
        } else {
            if (init < end) Direction.UP
            else Direction.DOWN
        }
        val list = listTargetVertex.split("#".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        targetVertex = ArrayList()
        for (s in list) targetVertex.add(s.toInt())
        val listColor = listOfColors.split("#".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        color = Color(listColor[0].toInt(), listColor[1].toInt(), listColor[2].toInt())
    }
}