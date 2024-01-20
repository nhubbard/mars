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

package edu.missouristate.mars.util

import java.awt.Font
import java.awt.GraphicsEnvironment
import kotlin.math.max
import kotlin.math.min

/**
 * Specialized Font class designed to be used by both the settings menu methods and the Settings class.
 */
object EditorFont {
    enum class FontStyles(val styleName: String, val constant: Int) {
        PLAIN("Plain", Font.PLAIN),
        BOLD("Bold", Font.BOLD),
        ITALIC("Italic", Font.ITALIC),
        BOLD_ITALIC("Bold + Italic", Font.BOLD or Font.ITALIC);

        companion object {
            @JvmStatic val styleStrings = entries.map(FontStyles::styleName).toTypedArray()
            @JvmStatic val styleConstants = entries.map(FontStyles::constant).toTypedArray()

            fun fromName(name: String): FontStyles =
                entries.firstOrNull { it.styleName == name } ?: PLAIN

            fun fromConstant(constant: Int): FontStyles =
                entries.firstOrNull { it.constant == constant } ?: PLAIN
        }
    }

    @JvmStatic val DEFAULT_STYLE = FontStyles.PLAIN

    const val DEFAULT_SIZE = 12
    const val MIN_SIZE = 6
    const val MAX_SIZE = 72

    @JvmStatic val allCommonFamilies =
        arrayOf("Arial", "Courier New", "Georgia", "Lucida Sans Typewriter", "Times New Roman", "Verdana")

    /**
     * Obtain an array of all available font family names. These are guaranteed to be available at runtime, as they come
     * from the local GraphicsEnvironment.
     */
    @JvmStatic val allFamilies: Array<String> = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames

    @JvmStatic val fontStyleStrings: Array<String> = FontStyles.styleStrings

    @JvmStatic val commonFamilies = actualCommonFamilies()

    private const val TAB_STRING = "\t"
    private const val TAB_CHAR = '\t'
    private const val SPACES = "                                                  "

    /**
     * Given an int representing the font size, returns corresponding string.
     *
     * @param size Int representing size.
     * @return String value of parameter, unless it is less than MIN_SIZE (returns MIN_SIZE
     * as String) or greater than MAX_SIZE (returns MAX_SIZE as String).
     */
    @JvmStatic
    fun sizeIntToSizeString(size: Int): String = max(min(size, MAX_SIZE), MIN_SIZE).toString()

    /**
     * Given a String representing font size, returns corresponding int.
     *
     * @param size String representing size.
     * @return int value of parameter, unless it is less than MIN_SIZE (returns
     * MIN_SIZE) or greater than MAX_SIZE (returns MAX_SIZE).  If the string
     * cannot be parsed as a decimal integer, it returns DEFAULT_SIZE.
     */
    @JvmStatic
    fun sizeStringToSizeInt(size: String) = max(min(size.toIntOrNull() ?: DEFAULT_SIZE, MAX_SIZE), MIN_SIZE)

    /**
     * Creates a new Font object based on the given String specifications.  This
     * is not the same as {@link java.awt.Font}'s constructor, which requires integer values for style and size.
     * It assures that defaults and size limits are applied when necessary.
     *
     * @param family String containing font family.
     * @param style  String containing font style.  A list of available styles can
     *               be obtained from getFontStyleStrings().  The default of styleStringToStyleInt()
     *               is substituted if necessary.
     * @param size   String containing font size.  The defaults and limits of
     *               sizeStringToSizeInt() are substituted if necessary.
     */
    @JvmStatic
    fun createFontFromStringValues(family: String, style: String, size: String): Font =
        Font(family, FontStyles.fromName(style).constant, sizeStringToSizeInt(size))

    /**
     * Handy utility to produce a string that substitutes spaces for all tab characters
     * in the given string.  The number of spaces generated is based on the position of
     * the tab character and the specified tab size.
     *
     * @param string  The original string
     * @param tabSize The number of spaces each tab character represents
     * @return New string in which spaces are substituted for tabs
     * @throws NullPointerException if string is null
     */
    @JvmStatic
    @JvmOverloads
    fun substituteSpacesForTabs(string: String, tabSize: Int = 4): String {
        if (!string.contains(TAB_STRING)) return string
        val result = StringBuilder(string)
        for (i in result.indices)
            if (result[i] == TAB_CHAR)
                result.replace(i, i + 1, SPACES.substring(0, tabSize - (i % tabSize)))
        return result.toString()
    }

    @JvmStatic
    private fun actualCommonFamilies(): Array<String> =
        allCommonFamilies.filter {
            it in GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        }.toTypedArray()
}