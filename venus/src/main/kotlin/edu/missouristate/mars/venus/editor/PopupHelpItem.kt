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

package edu.missouristate.mars.venus.editor

import edu.missouristate.mars.venus.actions.HelpHelpAction
import kotlin.math.min

/**
 * Create popup help item. This is created as the result of either an exact-match or
 * prefix-match search. Note that prefix-match search includes exact as well as partial matches.
 *
 * @param tokenText   The document text that matched
 * @param example     An example instruction
 * @param description A textual description of the instruction
 * @param isExact     True if match occurred as the result of exact-match search. Defaults to true.
 */
class PopupHelpItem @JvmOverloads constructor(
    val tokenText: String,
    val example: String,
    description: String?,
    val isExact: Boolean = true
) {
    companion object {
        /**
         * Utility method to traverse ArrayList of PopupHelpItem objects and return String length of the longest example.
         */
        @JvmStatic
        fun maxExampleLength(matches: ArrayList<PopupHelpItem>): Int =
            matches.maxOfOrNull { it.exampleLength } ?: 0
    }

    var description: String? = if (isExact) description else {
        val detailPosition = description?.indexOf(HelpHelpAction.descriptionDetailSeparator) ?: -1
        if (detailPosition == -1) description else description!!.substring(0, detailPosition)
    }
    val exampleLength: Int get() = example.length

    @Deprecated(
        "Renamed to padExampleToLength.",
        ReplaceWith("padExampleToLength(length)"),
        DeprecationLevel.ERROR
    )
    fun getExamplePaddedToLength(length: Int) = padExampleToLength(length)

    fun padExampleToLength(length: Int): String =
        if (length > exampleLength) {
            val numSpaces = min(length - exampleLength, 40)
            example + " ".repeat(numSpaces)
        } else if (length == exampleLength) {
            example
        } else {
            example.substring(0, length)
        }
}