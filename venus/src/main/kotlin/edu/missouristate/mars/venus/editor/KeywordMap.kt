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

import edu.missouristate.mars.venus.editor.SyntaxUtilities.regionMatches
import edu.missouristate.mars.venus.editor.marker.Token
import javax.swing.text.Segment

/**
 * Creates a new [KeywordMap].
 *
 * @param ignoreCase True if the keys are case insensitive
 * @param mapLength  The number of `buckets' to create.
 *                   A value of 52 will give good performance for most maps.
 */
class KeywordMap @JvmOverloads constructor (
    var ignoreCase: Boolean,
    private val mapLength: Int = 52
) {
    private val map: Array<Keyword?> = arrayOfNulls(mapLength)

    /**
     * Looks up a key.
     *
     * @param text   The text segment
     * @param offset The offset of the substring within the text segment
     * @param length The length of the substring
     */
    fun lookup(text: Segment, offset: Int, length: Int): Token.Type {
        if (length == 0) return Token.Type.NULL
        if (text.array[offset] == '%') return Token.Type.MACRO_ARG
        var k = map[getSegmentMapKey(text, offset, length)]
        while (k != null) {
            if (length != k.keyword.size) {
                k = k.next
                continue
            }
            if (text.regionMatches(k.keyword, offset, ignoreCase)) return k.type
            k = k.next
        }
        return Token.Type.NULL
    }

    /**
     * Adds a key-value mapping.
     *
     * @param keyword The key
     * @param type    The value
     */
    fun add(keyword: String, type: Token.Type) {
        val key = getStringMapKey(keyword)
        map[key] = Keyword(keyword.toCharArray(), type, map[key])
    }

    private fun getStringMapKey(s: String): Int =
        (s[0].uppercaseChar().digitToInt() + s.last().uppercaseChar().digitToInt()) % mapLength

    private fun getSegmentMapKey(s: Segment, offset: Int, length: Int): Int =
        (s.array[offset].uppercaseChar().digitToInt() +
            s.array[offset + length - 1].uppercaseChar().digitToInt()) % mapLength

    data class Keyword(
        val keyword: CharArray,
        val type: Token.Type,
        val next: Keyword? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Keyword

            if (!keyword.contentEquals(other.keyword)) return false
            if (type != other.type) return false

            return true
        }

        override fun hashCode(): Int {
            var result = keyword.contentHashCode()
            result = 31 * result + type.hashCode()
            return result
        }
    }
}