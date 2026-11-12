package edu.missouristate.mars

import edu.missouristate.mars.api.EventEmitter
import edu.missouristate.mars.core.SimulationSettings
import edu.missouristate.mars.util.Binary
import java.lang.Integer.decode
import java.lang.Integer.parseInt
import java.util.StringTokenizer
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import kotlin.jvm.JvmRecord

/*
Copyright (c) 2003-2013,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** Stores user preferences and provides a live view of the current session settings. */
open class Settings internal constructor(private val preferences: Preferences) {
    constructor() : this(Preferences.userNodeForPackage(Settings::class.java))

    private val settingsEvents = EventEmitter<Settings>()
    private val defaultBooleans = booleanArrayOf(
        true, false, false, false, false, true, true, false, false, true, false, false, true, true,
        false, true, true, false, false, true, false,
    )
    private val booleans = defaultBooleans.copyOf()
    private val strings = TextPreferences(stringSettingsKeys, arrayOf("", "0 1 2 3 4", "0", "", "500", "8", "2"))
    private val fontFamilies = TextPreferences(fontFamilySettingsKeys, Array(fontFamilySettingsKeys.size) { "Monospaced" })
    private val fontStyles = TextPreferences(fontStyleSettingsKeys, Array(fontStyleSettingsKeys.size) { "Plain" })
    private val fontSizes = TextPreferences(fontSizeSettingsKeys, Array(fontSizeSettingsKeys.size) { "12" })
    private val colors = TextPreferences(colorSettingsKeys, arrayOf(
        "0x00e0e0e0", "0", "0x00ffffff", "0", "0x00ffff99", "0",
        "0x0033ff00", "0", "0x0099ccff", "0", "0x0099cc55", "0",
    ))
    private val textPreferences = listOf(strings, fontFamilies, fontStyles, fontSizes, colors)
    private var syntaxStyles: SyntaxStyles? = null

    open val simulationSettings = SimulationSettings(this)
    private var errorLimit = ApplicationInfo.maximumErrorMessages
    open var assemblerErrorLimit: Int
        get() = errorLimit
        set(value) {
            errorLimit = value
        }
    private var backstepStatus: BooleanSupplier? = BooleanSupplier { false }

    init {
        initialize()
    }

    open fun subscribeChange(listener: Consumer<Settings>) {
        settingsEvents.subscribe(listener)
    }

    open fun unsubscribeChange(listener: Consumer<Settings>) {
        settingsEvents.unsubscribe(listener)
    }

    open fun setBackstepStatus(status: BooleanSupplier?) {
        backstepStatus = status
    }

    open val backSteppingEnabled: Boolean get() = backstepStatus!!.asBoolean

    open fun reset() {
        initialize()
    }

    open fun getBooleanSetting(id: Int): Boolean {
        checkBooleanId(id)
        return booleans[id]
    }

    open fun setBooleanSetting(id: Int, value: Boolean) {
        checkBooleanId(id)
        if (booleans[id] != value) {
            booleans[id] = value
            persist { preferences.putBoolean(booleanSettingsKeys[id], value) }
            settingsEvents.emit(this)
        }
    }

    open fun setBooleanSettingNonPersistent(id: Int, value: Boolean) {
        checkBooleanId(id)
        booleans[id] = value
    }

    private fun checkBooleanId(id: Int) {
        require(id in booleans.indices) { "Invalid boolean setting ID" }
    }

    open var exceptionHandler: String?
        get() = strings.values[EXCEPTION_HANDLER]
        set(value) = strings.save(EXCEPTION_HANDLER, value)

    open var memoryConfiguration: String?
        get() = strings.values[MEMORY_CONFIGURATION]
        set(value) = strings.save(MEMORY_CONFIGURATION, value)

    open fun setMemoryConfigurationNonPersistent(config: String?) {
        strings.values[MEMORY_CONFIGURATION] = config
    }

    @JvmRecord
    data class FontSpec(val family: String?, val style: String?, val size: String?)

    open fun getFontSpec(position: Int): FontSpec? =
        if (position in fontFamilies.values.indices) {
            FontSpec(fontFamilies.values[position], fontStyles.values[position], fontSizes.values[position])
        } else null

    open fun getDefaultFontSpec(position: Int): FontSpec? =
        if (position in fontFamilies.defaults.indices) {
            FontSpec(fontFamilies.defaults[position], fontStyles.defaults[position], fontSizes.defaults[position])
        } else null

    open fun setFontSpec(position: Int, font: FontSpec?) {
        if (position in fontFamilies.values.indices) {
            val spec = font!!
            fontFamilies.values[position] = spec.family
            fontStyles.values[position] = spec.style
            fontSizes.values[position] = spec.size
            fontFamilies.persistValue(position)
            fontStyles.persistValue(position)
            fontSizes.persistValue(position)
        }
        if (position == EDITOR_FONT) settingsEvents.emit(this)
    }

    open var textColumnOrder: IntArray
        get() = parseColumnOrder(strings.values[TEXT_COLUMN_ORDER]!!)
        set(value) = strings.save(TEXT_COLUMN_ORDER, value.joinToString(" ", postfix = if (value.isEmpty()) "" else " "))

    open var caretBlinkRate: Int
        get() = parseInteger(strings.values[CARET_BLINK_RATE]) ?: parseInt(strings.defaults[CARET_BLINK_RATE])
        set(value) = strings.save(CARET_BLINK_RATE, value.toString())

    open var editorTabSize: Int
        get() = parseInteger(strings.values[EDITOR_TAB_SIZE]) ?: defaultEditorTabSize
        set(value) = strings.save(EDITOR_TAB_SIZE, value.toString())

    open val defaultEditorTabSize: Int get() = parseInt(strings.defaults[EDITOR_TAB_SIZE])

    open var editorPopupPrefixLength: Int
        get() = parseInteger(strings.values[EDITOR_POPUP_PREFIX_LENGTH]) ?: 2
        set(value) = strings.save(EDITOR_POPUP_PREFIX_LENGTH, value.toString())

    open var labelSortState: String?
        get() = strings.values[LABEL_SORT_STATE]
        set(value) = strings.save(LABEL_SORT_STATE, value)

    open fun getColorSettingByKey(key: String): Int? = getColorSettingByPosition(colorSettingsKeys.indexOf(key))

    open fun getDefaultColorSettingByKey(key: String): Int? = getDefaultColorSettingByPosition(colorSettingsKeys.indexOf(key))

    open fun getColorSettingByPosition(position: Int): Int? = decodeColor(colors.values.getOrNull(position))

    open fun getDefaultColorSettingByPosition(position: Int): Int? = decodeColor(colors.defaults.getOrNull(position))

    open fun setColorSettingByKey(key: String, color: Int) {
        val position = colorSettingsKeys.indexOf(key)
        if (position >= 0) setColorSettingByPosition(position, color)
    }

    open fun setColorSettingByPosition(position: Int, color: Int) {
        if (position in colors.values.indices) colors.save(position, encodeColor(color))
    }

    open fun initializeSyntaxStyleDefaults(colors: Array<String>, bolds: BooleanArray, italics: BooleanArray) {
        syntaxStyles = SyntaxStyles(colors, bolds, italics).also { it.readPreferences() }
    }

    open fun setEditorSyntaxStyleByPosition(index: Int, color: Int, italic: Boolean, bold: Boolean) {
        val styles = syntaxStyles!!
        styles.colors[index] = encodeColor(color)
        styles.italics[index] = italic
        styles.bolds[index] = bold
        persist {
            preferences.put("$SYNTAX_STYLE_COLOR_PREFIX$index", styles.colors[index])
            preferences.putBoolean("$SYNTAX_STYLE_BOLD_PREFIX$index", bold)
            preferences.putBoolean("$SYNTAX_STYLE_ITALIC_PREFIX$index", italic)
        }
    }

    open fun getEditorSyntaxStyleColorByPosition(index: Int): Int? = decodeColor(syntaxStyles!!.colors[index]) ?: 0

    open fun getEditorSyntaxStyleItalicByPosition(index: Int): Boolean = syntaxStyles!!.italics[index]

    open fun getEditorSyntaxStyleBoldByPosition(index: Int): Boolean = syntaxStyles!!.bolds[index]

    open fun getDefaultEditorSyntaxStyleColorByPosition(index: Int): Int? = decodeColor(syntaxStyles!!.defaultColors[index]) ?: 0

    open fun getDefaultEditorSyntaxStyleItalicByPosition(index: Int): Boolean = syntaxStyles!!.defaultItalics[index]

    open fun getDefaultEditorSyntaxStyleBoldByPosition(index: Int): Boolean = syntaxStyles!!.defaultBolds[index]

    private fun initialize() {
        errorLimit = ApplicationInfo.maximumErrorMessages
        defaultBooleans.copyInto(booleans)
        for (setting in textPreferences) setting.defaults.copyInto(setting.values)
        if (!readResourceDefaults()) {
            println("MARS System error: unable to read Settings.properties defaults. Using built-in defaults.")
        }
        for (index in booleans.indices) {
            booleans[index] = preferences.getBoolean(booleanSettingsKeys[index], booleans[index])
        }
        for (setting in textPreferences) setting.readPreferences()
        syntaxStyles?.readPreferences()
    }

    private fun readResourceDefaults(): Boolean = try {
        for (index in booleans.indices) {
            val raw = ApplicationInfo.getPropertyEntry(SETTINGS_RESOURCE, booleanSettingsKeys[index]) ?: continue
            val value = raw.toBoolean()
            defaultBooleans[index] = value
            booleans[index] = value
        }
        for (setting in textPreferences) setting.readResourceDefaults()
        true
    } catch (_: Exception) {
        false
    }

    private inner class TextPreferences(private val keys: Array<String>, val defaults: Array<String?>) {
        val values = defaults.copyOf()

        fun readResourceDefaults() {
            for (index in keys.indices) {
                val value = ApplicationInfo.getPropertyEntry(SETTINGS_RESOURCE, keys[index]) ?: continue
                defaults[index] = value
                values[index] = value
            }
        }

        fun readPreferences() {
            for (index in keys.indices) values[index] = preferences.get(keys[index], values[index])
        }

        fun save(index: Int, value: String?) {
            values[index] = value
            persistValue(index)
        }

        fun persistValue(index: Int) {
            persist { preferences.put(keys[index], values[index]) }
        }
    }

    private inner class SyntaxStyles(colors: Array<String>, bolds: BooleanArray, italics: BooleanArray) {
        val defaultColors = colors.copyOf()
        val defaultBolds = bolds.copyOf()
        val defaultItalics = italics.copyOf()
        val colors = colors.copyOf()
        val bolds = bolds.copyOf()
        val italics = italics.copyOf()

        fun readPreferences() {
            for (index in colors.indices) {
                colors[index] = preferences.get("$SYNTAX_STYLE_COLOR_PREFIX$index", colors[index])
                bolds[index] = preferences.getBoolean("$SYNTAX_STYLE_BOLD_PREFIX$index", bolds[index])
                italics[index] = preferences.getBoolean("$SYNTAX_STYLE_ITALIC_PREFIX$index", italics[index])
            }
        }
    }

    private inline fun persist(write: () -> Unit) {
        try {
            write()
            preferences.flush()
        } catch (_: SecurityException) {
            // Preferences remain available in memory.
        } catch (_: BackingStoreException) {
            // Preferences remain available in memory.
        }
    }

    private fun parseColumnOrder(value: String): IntArray {
        val tokens = StringTokenizer(value)
        val columns = IntArray(tokens.countTokens())
        for (index in columns.indices) {
            val column = parseInteger(tokens.nextToken())
            if (column == null) {
                val fallback = strings.defaults[TEXT_COLUMN_ORDER]!!
                return if (value == fallback) columns else parseColumnOrder(fallback)
            }
            columns[index] = column
        }
        return columns
    }

    private fun parseInteger(value: String?): Int? = try {
        parseInt(value)
    } catch (_: NumberFormatException) {
        null
    }

    private fun encodeColor(color: Int): String = Binary.intToHexString(color and 0xffffff)

    private fun decodeColor(value: String?): Int? {
        if (value == null) return null
        return try {
            decode(value)
        } catch (_: NumberFormatException) {
            null
        }
    }
    companion object {
        private const val SETTINGS_RESOURCE = "Settings"
        private const val SYNTAX_STYLE_COLOR_PREFIX = "SyntaxStyleColor_"
        private const val SYNTAX_STYLE_BOLD_PREFIX = "SyntaxStyleBold_"
        private const val SYNTAX_STYLE_ITALIC_PREFIX = "SyntaxStyleItalic_"

        const val EXTENDED_ASSEMBLER_ENABLED = 0
        const val BARE_MACHINE_ENABLED = 1
        const val ASSEMBLE_ON_OPEN_ENABLED = 2
        const val ASSEMBLE_ALL_ENABLED = 3
        const val LABEL_WINDOW_VISIBILITY = 4
        const val DISPLAY_ADDRESSES_IN_HEX = 5
        const val DISPLAY_VALUES_IN_HEX = 6
        const val EXCEPTION_HANDLER_ENABLED = 7
        const val DELAYED_BRANCHING_ENABLED = 8
        const val EDITOR_LINE_NUMBERS_DISPLAYED = 9
        const val WARNINGS_ARE_ERRORS = 10
        const val PROGRAM_ARGUMENTS = 11
        const val DATA_SEGMENT_HIGHLIGHTING = 12
        const val REGISTERS_HIGHLIGHTING = 13
        const val START_AT_MAIN = 14
        const val EDITOR_CURRENT_LINE_HIGHLIGHTING = 15
        const val POPUP_INSTRUCTION_GUIDANCE = 16
        const val POPUP_SYSCALL_INPUT = 17
        const val GENERIC_TEXT_EDITOR = 18
        const val AUTO_INDENT = 19
        const val SELF_MODIFYING_CODE_ENABLED = 20

        const val EXCEPTION_HANDLER = 0
        const val TEXT_COLUMN_ORDER = 1
        const val LABEL_SORT_STATE = 2
        const val MEMORY_CONFIGURATION = 3
        const val CARET_BLINK_RATE = 4
        const val EDITOR_TAB_SIZE = 5
        const val EDITOR_POPUP_PREFIX_LENGTH = 6

        const val EDITOR_FONT = 0
        const val EVEN_ROW_FONT = 1
        const val ODD_ROW_FONT = 2
        const val TEXTSEGMENT_HIGHLIGHT_FONT = 3
        const val TEXTSEGMENT_DELAYSLOT_HIGHLIGHT_FONT = 4
        const val DATASEGMENT_HIGHLIGHT_FONT = 5
        const val REGISTER_HIGHLIGHT_FONT = 6

        const val EVEN_ROW_BACKGROUND = 0
        const val EVEN_ROW_FOREGROUND = 1
        const val ODD_ROW_BACKGROUND = 2
        const val ODD_ROW_FOREGROUND = 3
        const val TEXTSEGMENT_HIGHLIGHT_BACKGROUND = 4
        const val TEXTSEGMENT_HIGHLIGHT_FOREGROUND = 5
        const val TEXTSEGMENT_DELAYSLOT_HIGHLIGHT_BACKGROUND = 6
        const val TEXTSEGMENT_DELAYSLOT_HIGHLIGHT_FOREGROUND = 7
        const val DATASEGMENT_HIGHLIGHT_BACKGROUND = 8
        const val DATASEGMENT_HIGHLIGHT_FOREGROUND = 9
        const val REGISTER_HIGHLIGHT_BACKGROUND = 10
        const val REGISTER_HIGHLIGHT_FOREGROUND = 11

        // Preference keys retain their exact spelling and public index.
        private val booleanSettingsKeys = arrayOf(
            "ExtendedAssembler",
            "BareMachine",
            "AssembleOnOpen",
            "AssembleAll",
            "LabelWindowVisibility",
            "DisplayAddressesInHex",
            "DisplayValuesInHex",
            "LoadExceptionHandler",
            "DelayedBranching",
            "EditorLineNumbersDisplayed",
            "WarningsAreErrors",
            "ProgramArguments",
            "DataSegmentHighlighting",
            "RegistersHighlighting",
            "StartAtMain",
            "EditorCurrentLineHighlighting",
            "PopupInstructionGuidance",
            "PopupSyscallInput",
            "GenericTextEditor",
            "AutoIndent",
            "SelfModifyingCode",
        )

        private val stringSettingsKeys = arrayOf(
            "ExceptionHandler",
            "TextColumnOrder",
            "LabelSortState",
            "MemoryConfiguration",
            "CaretBlinkRate",
            "EditorTabSize",
            "EditorPopupPrefixLength",
        )

        private val fontFamilySettingsKeys = arrayOf(
            "EditorFontFamily",
            "EvenRowFontFamily",
            "OddRowFontFamily",
            " TextSegmentHighlightFontFamily",
            "TextSegmentDelayslotHighightFontFamily",
            "DataSegmentHighlightFontFamily",
            "RegisterHighlightFontFamily",
        )

        private val fontStyleSettingsKeys = arrayOf(
            "EditorFontStyle",
            "EvenRowFontStyle",
            "OddRowFontStyle",
            " TextSegmentHighlightFontStyle",
            "TextSegmentDelayslotHighightFontStyle",
            "DataSegmentHighlightFontStyle",
            "RegisterHighlightFontStyle",
        )

        private val fontSizeSettingsKeys = arrayOf(
            "EditorFontSize",
            "EvenRowFontSize",
            "OddRowFontSize",
            " TextSegmentHighlightFontSize",
            "TextSegmentDelayslotHighightFontSize",
            "DataSegmentHighlightFontSize",
            "RegisterHighlightFontSize",
        )

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
            "RegisterHighlightForeground",
        )

    }
}
