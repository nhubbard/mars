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

package edu.missouristate.mars

import edu.missouristate.mars.util.Binary
import edu.missouristate.mars.util.EditorFont
import edu.missouristate.mars.venus.editor.SyntaxStyle
import edu.missouristate.mars.venus.editor.SyntaxUtilities
import edu.missouristate.mars.venus.editor.marker.Token
import java.awt.Color
import java.awt.Font
import java.util.prefs.BackingStoreException
import java.util.stream.IntStream

class UISettings : CoreSettings() {
    companion object {
        /**
         * Font for the text editor
         */
        const val EDITOR_FONT: Int = 0

        /**
         * Font for even table rows (text, data, register displays)
         */
        const val EVEN_ROW_FONT: Int = 1

        /**
         * Font for odd table rows (text, data, register displays)
         */
        const val ODD_ROW_FONT: Int = 2

        /**
         * Font for table odd row foreground (text, data, register displays)
         */
        const val TEXT_SEGMENT_HIGHLIGHT_FONT: Int = 3

        /**
         * Font for text segment delay slot highlighted background
         */
        const val TEXT_SEGMENT_DELAY_SLOT_HIGHLIGHT_FONT: Int = 4

        /**
         * Font for text segment highlighted background
         */
        const val DATA_SEGMENT_HIGHLIGHT_FONT: Int = 5

        /**
         * Font for the register highlighted background
         */
        const val REGISTER_HIGHLIGHT_FONT: Int = 6

        private val fontFamilySettingsKeys = arrayOf(
            "EditorFontFamily",
            "EvenRowFontFamily",
            "OddRowFontFamily",
            " TextSegmentHighlightFontFamily",
            "TextSegmentDelayslotHighightFontFamily",
            "DataSegmentHighlightFontFamily",
            "RegisterHighlightFontFamily"
        )

        private val fontStyleSettingsKeys = arrayOf(
            "EditorFontStyle",
            "EvenRowFontStyle",
            "OddRowFontStyle",
            " TextSegmentHighlightFontStyle",
            "TextSegmentDelayslotHighightFontStyle",
            "DataSegmentHighlightFontStyle",
            "RegisterHighlightFontStyle"
        )

        private val fontSizeSettingsKeys = arrayOf(
            "EditorFontSize",
            "EvenRowFontSize",
            "OddRowFontSize",
            " TextSegmentHighlightFontSize",
            "TextSegmentDelayslotHighightFontSize",
            "DataSegmentHighlightFontSize",
            "RegisterHighlightFontSize"
        )

        /**
         * Last resort default values for Font settings;
         * will use only if neither the Preferences nor the properties file work.
         * If you wish to change, do so before instantiating the Settings class.
         * Must match key by list position shown above.
         */
        // DPS 3-Oct-2012
        // Changed the default font family from "Courier New" to "Monospaced" after receiving reports that Mac were not
        // correctly rendering the left parenthesis character in the editor or text segment display.
        // See http://www.mirthcorp.com/community/issues/browse/MIRTH-1921?page=com.atlassian.jira.plugin.system.issuetabpanels:all-tabpanel
        private val defaultFontFamilySettingsValues =
            arrayOf("Monospaced", "Monospaced", "Monospaced", "Monospaced", "Monospaced", "Monospaced", "Monospaced")
        private val defaultFontStyleSettingsValues =
            arrayOf("Plain", "Plain", "Plain", "Plain", "Plain", "Plain", "Plain")
        private val defaultFontSizeSettingsValues = arrayOf("12", "12", "12", "12", "12", "12", "12")

        // COLOR SETTINGS.  Each array position has an associated name.
        /**
         * RGB color for table even row background (text, data, register displays)
         */
        const val EVEN_ROW_BACKGROUND: Int = 0

        /**
         * RGB color for table even row foreground (text, data, register displays)
         */
        const val EVEN_ROW_FOREGROUND: Int = 1

        /**
         * RGB color for table odd row background (text, data, register displays)
         */
        const val ODD_ROW_BACKGROUND: Int = 2

        /**
         * RGB color for table odd row foreground (text, data, register displays)
         */
        const val ODD_ROW_FOREGROUND: Int = 3

        /**
         * RGB color for text segment highlighted background
         */
        const val TEXTSEGMENT_HIGHLIGHT_BACKGROUND: Int = 4

        /**
         * RGB color for text segment highlighted foreground
         */
        const val TEXTSEGMENT_HIGHLIGHT_FOREGROUND: Int = 5

        /**
         * RGB color for text segment delay slot highlighted background
         */
        const val TEXTSEGMENT_DELAYSLOT_HIGHLIGHT_BACKGROUND: Int = 6

        /**
         * RGB color for text segment delay slot highlighted foreground
         */
        const val TEXTSEGMENT_DELAYSLOT_HIGHLIGHT_FOREGROUND: Int = 7

        /**
         * RGB color for text segment highlighted background
         */
        const val DATASEGMENT_HIGHLIGHT_BACKGROUND: Int = 8

        /**
         * RGB color for text segment highlighted foreground
         */
        const val DATASEGMENT_HIGHLIGHT_FOREGROUND: Int = 9

        /**
         * RGB color for register highlighted background
         */
        const val REGISTER_HIGHLIGHT_BACKGROUND: Int = 10

        /**
         * RGB color for register highlighted foreground
         */
        const val REGISTER_HIGHLIGHT_FOREGROUND: Int = 11

        // Match the above by position.
        private val colorSettingsKeys = arrayOf(
            "EvenRowBackground",
            "EvenRowForeground",
            "OddRowBackground",
            "OddRowForeground",
            "TextSegmentHighlightBackground",
            "TextSegmentHighlightForeground",
            "TextSegmentDelaySlotHighlightBackground",
            "TextSegmentDelaySlotHighlightForeground",
            "DataSegmentHighlightBackground",
            "DataSegmentHighlightForeground",
            "RegisterHighlightBackground",
            "RegisterHighlightForeground"
        )

        /**
         * Last resort default values for color settings;
         * will use only if neither the Preferences nor the properties file work.
         * If you wish to change, do so before instantiating the Settings class.
         * Must match key by list position.
         */
        private val defaultColorSettingsValues = arrayOf(
            "0x00e0e0e0",
            "0",
            "0x00ffffff",
            "0",
            "0x00ffff99",
            "0",
            "0x0033ff00",
            "0",
            "0x0099ccff",
            "0",
            "0x0099cc55",
            "0"
        )
        private const val SYNTAX_STYLE_COLOR_PREFIX = "SyntaxStyleColor_"
        private const val SYNTAX_STYLE_BOLD_PREFIX = "SyntaxStyleBold_"
        private const val SYNTAX_STYLE_ITALIC_PREFIX = "SyntaxStyleItalic_"
    }

    private var fontFamilySettingsValues: Array<String> = Array(fontFamilySettingsKeys.size) { "" }
    private var fontStyleSettingsValues: Array<String> = Array(fontStyleSettingsKeys.size) { "" }
    private var fontSizeSettingsValues: Array<String> = Array(fontSizeSettingsKeys.size) { "" }
    private var colorSettingsValues: Array<String> = Array(colorSettingsKeys.size) { "" }

    /**
     * Current editor font.  Retained for compatibility but replaced
     * by: getFontByPosition(Settings.EDITOR_FONT)
     *
     * @return Font object for current editor font.
     */
    fun getEditorFont(): Font? = getFontByPosition(EDITOR_FONT)

    /**
     * Retrieve a Font setting
     *
     * @param fontSettingPosition constant that identifies which item
     * @return Font object for given item
     */
    fun getFontByPosition(fontSettingPosition: Int): Font? =
        if (fontSettingPosition >= 0 && fontSettingPosition < fontFamilySettingsValues.size) {
            EditorFont.createFontFromStringValues(
                fontFamilySettingsValues[fontSettingPosition],
                fontStyleSettingsValues[fontSettingPosition],
                fontSizeSettingsValues[fontSettingPosition]
            )
        } else {
            null
        }

    /**
     * Retrieve a default Font setting
     *
     * @param fontSettingPosition constant that identifies which item
     * @return Font object for given item
     */
    fun getDefaultFontByPosition(fontSettingPosition: Int): Font? =
        if (fontSettingPosition >= 0 && fontSettingPosition < defaultFontFamilySettingsValues.size) {
            EditorFont.createFontFromStringValues(
                defaultFontFamilySettingsValues[fontSettingPosition],
                defaultFontStyleSettingsValues[fontSettingPosition],
                defaultFontSizeSettingsValues[fontSettingPosition]
            )
        } else null

    /**
     * Get the Color object for specified settings key.
     * Returns null if key is not found or its value is not a valid color encoding.
     *
     * @param key the Setting key
     * @return corresponding Color, or null if key not found or value not valid color
     */
    fun getColorSettingByKey(key: String): Color? {
        return getColorValueByKey(key, colorSettingsValues)
    }

    /**
     * Get the default Color value for specified settings key.
     * Returns null if key is not found or its value is not a valid color encoding.
     *
     * @param key the Setting key
     * @return corresponding default Color, or null if key not found or value not valid color
     */
    fun getDefaultColorSettingByKey(key: String): Color? {
        return getColorValueByKey(key, defaultColorSettingsValues)
    }

    /**
     * Get the Color object for specified settings name (a static constant).
     * Returns null if argument invalid or its value is not a valid color encoding.
     *
     * @param position the Setting name (see the list of static constants)
     * @return corresponding Color, or null if argument invalid or value not valid color
     */
    fun getColorSettingByPosition(position: Int): Color? {
        return getColorValueByPosition(position, colorSettingsValues)
    }

    /**
     * Get the default Color object for specified settings name (a static constant).
     * Returns null if argument invalid or its value is not a valid color encoding.
     *
     * @param position the Setting name (see the list of static constants)
     * @return corresponding default Color, or null if argument invalid or value not valid color
     */
    fun getDefaultColorSettingByPosition(position: Int): Color? {
        return getColorValueByPosition(position, defaultColorSettingsValues)
    }

    /**
     * Set the editor font to the specified Font object and write it to persistent storage.
     * This method is retained for compatibility but replaced by:
     * setFontByPosition(Settings.EDITOR_FONT, font)
     *
     * @param font Font object to be used by text editor.
     */
    fun setEditorFont(font: Font) {
        setFontByPosition(EDITOR_FONT, font)
    }

    /**
     * Store a Font setting
     *
     * @param fontSettingPosition Constant that identifies the item the font goes with
     * @param font                The font to set that item to
     */
    fun setFontByPosition(fontSettingPosition: Int, font: Font) {
        if (fontSettingPosition >= 0 && fontSettingPosition < fontFamilySettingsValues.size) {
            fontFamilySettingsValues[fontSettingPosition] = font.family
            fontStyleSettingsValues[fontSettingPosition] = EditorFont.FontStyles.fromConstant(font.style).styleName
            fontSizeSettingsValues[fontSettingPosition] = EditorFont.sizeIntToSizeString(font.size)
            saveFontSetting(fontSettingPosition, fontFamilySettingsKeys, fontFamilySettingsValues)
            saveFontSetting(fontSettingPosition, fontStyleSettingsKeys, fontStyleSettingsValues)
            saveFontSetting(fontSettingPosition, fontSizeSettingsKeys, fontSizeSettingsValues)
        }
        if (fontSettingPosition == EDITOR_FONT) {
            setChanged()
            notifyObservers()
        }
    }

    /**
     * Set Color object for specified settings key.  Has no effect if key is invalid.
     *
     * @param key   the Setting key
     * @param color the Color to save
     */
    fun setColorSettingByKey(key: String, color: Color) {
        for (i in colorSettingsKeys.indices) {
            if (key == colorSettingsKeys[i]) {
                setColorSettingByPosition(i, color)
                return
            }
        }
    }

    override fun applyDefaultSettings() {
        super.applyDefaultSettings()
        IntStream.range(0, fontFamilySettingsValues.size).forEach { i: Int ->
            fontFamilySettingsValues[i] = defaultFontFamilySettingsValues[i]
            fontStyleSettingsValues[i] = defaultFontStyleSettingsValues[i]
            fontSizeSettingsValues[i] = defaultFontSizeSettingsValues[i]
        }
        System.arraycopy(defaultColorSettingsValues, 0, colorSettingsValues, 0, colorSettingsValues.size)
        initializeEditorSyntaxStyles()
    }

    /**
     * Used by setter methods for color-based
     */
    private fun setColorSetting(settingIndex: Int, color: Color) {
        colorSettingsValues[settingIndex] = Binary.intToHexString(color.red shl 16 or (color.green shl 8) or color.blue)
        saveColorSetting(settingIndex)
    }

    /**
     * Get the [java.awt.Color] object for this key value.
     * Get it from the values array provided as argument (could be either the current or the default settings array).
     */
    private fun getColorValueByKey(key: String, values: Array<String>): Color? {
        for (i in colorSettingsKeys.indices) {
            if (key == colorSettingsKeys[i]) {
                return getColorValueByPosition(i, values)
            }
        }
        return null
    }

    /**
     * Get the [java.awt.Color] object for this key array position.
     * Get it from the values array provided as argument (could be either the current or the default settings array).
     */
    private fun getColorValueByPosition(position: Int, values: Array<String>): Color? {
        var color: Color? = null
        if (position >= 0 && position < colorSettingsKeys.size) {
            try {
                color = Color.decode(values[position])
            } catch (ignored: NumberFormatException) {}
        }
        return color
    }

    override fun readSettingsFromPropertiesFile(filename: String): Boolean {
        val superStatus = super.readSettingsFromPropertiesFile(filename)
        var settingValue: String?
        try {
            for (i in fontFamilySettingsValues.indices) {
                settingValue = Globals.getPropertyEntry(filename, fontFamilySettingsKeys[i])
                if (settingValue != null) {
                    defaultFontFamilySettingsValues[i] = settingValue
                    fontFamilySettingsValues[i] = defaultFontFamilySettingsValues[i]
                }
                settingValue = Globals.getPropertyEntry(filename, fontStyleSettingsKeys[i])
                if (settingValue != null) {
                    defaultFontStyleSettingsValues[i] = settingValue
                    fontStyleSettingsValues[i] = defaultFontStyleSettingsValues[i]
                }
                settingValue = Globals.getPropertyEntry(filename, fontSizeSettingsKeys[i])
                if (settingValue != null) {
                    defaultFontSizeSettingsValues[i] = settingValue
                    fontSizeSettingsValues[i] = defaultFontSizeSettingsValues[i]
                }
            }
            for (i in colorSettingsKeys.indices) {
                settingValue = Globals.getPropertyEntry(filename, colorSettingsKeys[i])
                if (settingValue != null) {
                    defaultColorSettingsValues[i] = settingValue
                    colorSettingsValues[i] = defaultColorSettingsValues[i]
                }
            }
        } catch (e: Exception) {
            return false
        }
        return superStatus
    }

    override fun getSettingsFromPreferences() {
        super.getSettingsFromPreferences()
        for (i in fontFamilySettingsKeys.indices) {
            fontFamilySettingsValues[i] = preferences[fontFamilySettingsKeys[i], fontFamilySettingsValues[i]]
            fontStyleSettingsValues[i] = preferences[fontStyleSettingsKeys[i], fontStyleSettingsValues[i]]
            fontSizeSettingsValues[i] = preferences[fontSizeSettingsKeys[i], fontSizeSettingsValues[i]]
        }
        for (i in colorSettingsKeys.indices)
            colorSettingsValues[i] = preferences[colorSettingsKeys[i], colorSettingsValues[i]]
    }

    /**
     * Set Color object for specified settings name (a static constant). Has no effect if invalid.
     *
     * @param position the Setting name (see the list of static constants)
     * @param color    the Color to save
     */
    fun setColorSettingByPosition(position: Int, color: Color) {
        if (position >= 0 && position < colorSettingsKeys.size) setColorSetting(position, color)
    }

    /* **************************************************************************
     This section contains all code related to syntax highlighting styles settings.
     A style includes 3 components: color, bold (t/f), italic (t/f)

    The fallback defaults will come not from an array here, but from the
    existing static method SyntaxUtilities.getDefaultSyntaxStyles()
    in the edu.missouristate.mars.venus.editors.jeditsyntax package.  It returns an array
    of SyntaxStyle objects.

    */
    private lateinit var syntaxStyleColorSettingsValues: Array<String>
    private lateinit var syntaxStyleBoldSettingsValues: BooleanArray
    private lateinit var syntaxStyleItalicSettingsValues: BooleanArray

    private lateinit var syntaxStyleColorSettingsKeys: Array<String>
    private lateinit var syntaxStyleBoldSettingsKeys: Array<String>
    private lateinit var syntaxStyleItalicSettingsKeys: Array<String>

    private lateinit var defaultSyntaxStyleColorSettingsValues: Array<String>
    private lateinit var defaultSyntaxStyleBoldSettingsValues: BooleanArray
    private lateinit var defaultSyntaxStyleItalicSettingsValues: BooleanArray

    fun setEditorSyntaxStyleByPosition(index: Int, syntaxStyle: SyntaxStyle) {
        syntaxStyleColorSettingsValues[index] = syntaxStyle.colorAsHexString
        syntaxStyleItalicSettingsValues[index] = syntaxStyle.isItalic
        syntaxStyleBoldSettingsValues[index] = syntaxStyle.isBold
        saveEditorSyntaxStyle(index)
    }

    fun getEditorSyntaxStyleByPosition(index: Int): SyntaxStyle {
        return SyntaxStyle(
            getColorValueByPosition(index, syntaxStyleColorSettingsValues),
            syntaxStyleItalicSettingsValues[index],
            syntaxStyleBoldSettingsValues[index]
        )
    }

    fun getEditorSyntaxStyle(type: Token.Type): SyntaxStyle =
        getEditorSyntaxStyleByPosition(type.rawValue.toInt())

    fun getDefaultEditorSyntaxStyleByPosition(index: Int): SyntaxStyle {
        return SyntaxStyle(
            getColorValueByPosition(index, defaultSyntaxStyleColorSettingsValues),
            defaultSyntaxStyleItalicSettingsValues[index],
            defaultSyntaxStyleBoldSettingsValues[index]
        )
    }

    private fun saveEditorSyntaxStyle(index: Int) {
        try {
            preferences.put(syntaxStyleColorSettingsKeys[index], syntaxStyleColorSettingsValues[index])
            preferences.putBoolean(syntaxStyleBoldSettingsKeys[index], syntaxStyleBoldSettingsValues[index])
            preferences.putBoolean(syntaxStyleItalicSettingsKeys[index], syntaxStyleItalicSettingsValues[index])
            preferences.flush()
        } catch (se: SecurityException) {
            // cannot write to persistent storage for security reasons
        } catch (bse: BackingStoreException) {
            // unable to communicate with persistent storage (strange days)
        }
    }

    // For syntax styles, need to initialize from SyntaxUtilities defaults.
    // Taking care not to explicitly create a Color object, since it may trigger
    // Swing initialization (that caused problems for UC Berkeley when we
    // created Font objects here).  It shouldn't, but then again Font shouldn't
    // either, but they said it did.  (see HeadlessException)
    // On the other hand, the first statement of this method causes Color objects
    // to be created!  It is possible but a real pain in the rear to avoid using
    // Color objects totally.  Requires new methods for the SyntaxUtilities class.
    private fun initializeEditorSyntaxStyles() {
        val syntaxStyle = SyntaxUtilities.getDefaultSyntaxStyles()
        val tokens = syntaxStyle.size
        syntaxStyleColorSettingsKeys = Array(tokens) { "" }
        syntaxStyleBoldSettingsKeys = Array(tokens) { "" }
        syntaxStyleItalicSettingsKeys = Array(tokens) { "" }
        defaultSyntaxStyleColorSettingsValues = Array(tokens) { "" }
        defaultSyntaxStyleBoldSettingsValues = BooleanArray(tokens)
        defaultSyntaxStyleItalicSettingsValues = BooleanArray(tokens)
        syntaxStyleColorSettingsValues = Array(tokens) { "" }
        syntaxStyleBoldSettingsValues = BooleanArray(tokens)
        syntaxStyleItalicSettingsValues = BooleanArray(tokens)
        for (i in 0 until tokens) {
            syntaxStyleColorSettingsKeys[i] = SYNTAX_STYLE_COLOR_PREFIX + i
            syntaxStyleBoldSettingsKeys[i] = SYNTAX_STYLE_BOLD_PREFIX + i
            syntaxStyleItalicSettingsKeys[i] = SYNTAX_STYLE_ITALIC_PREFIX + i
            defaultSyntaxStyleColorSettingsValues[i] = syntaxStyle[i].colorAsHexString
            syntaxStyleColorSettingsValues[i] = defaultSyntaxStyleColorSettingsValues[i]
            defaultSyntaxStyleBoldSettingsValues[i] = syntaxStyle[i].isBold
            syntaxStyleBoldSettingsValues[i] = defaultSyntaxStyleBoldSettingsValues[i]
            defaultSyntaxStyleItalicSettingsValues[i] = syntaxStyle[i].isItalic
            syntaxStyleItalicSettingsValues[i] = defaultSyntaxStyleItalicSettingsValues[i]
        }
    }

    private fun getEditorSyntaxStyleSettingsFromPreferences() {
        for (i in syntaxStyleColorSettingsKeys.indices) {
            syntaxStyleColorSettingsValues[i] =
                preferences[syntaxStyleColorSettingsKeys[i], syntaxStyleColorSettingsValues[i]]
            syntaxStyleBoldSettingsValues[i] = preferences.getBoolean(
                syntaxStyleBoldSettingsKeys[i], syntaxStyleBoldSettingsValues[i]
            )
            syntaxStyleItalicSettingsValues[i] = preferences.getBoolean(
                syntaxStyleItalicSettingsKeys[i], syntaxStyleItalicSettingsValues[i]
            )
        }
    }

    /**
     * Save the key-value pair in the Properties object and assure it is written to persistent storage.
     */
    private fun saveFontSetting(index: Int, settingsKeys: Array<String>, settingsValues: Array<String>) =
        try {
            preferences.put(settingsKeys[index], settingsValues[index])
            preferences.flush()
        } catch (se: SecurityException) {
            // cannot write to persistent storage for security reasons
        } catch (bse: BackingStoreException) {
            // unable to communicate with persistent storage (strange days)
        }

    /**
     * Save the key-value pair in the Properties object and assure it is written to persistent storage.
     */
    private fun saveColorSetting(index: Int) =
        try {
            preferences.put(colorSettingsKeys[index], colorSettingsValues[index])
            preferences.flush()
        } catch (se: SecurityException) {
            // cannot write to persistent storage for security reasons
        } catch (bse: BackingStoreException) {
            // unable to communicate with persistent storage (strange days)
        }
}