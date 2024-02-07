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

@file:Suppress("DEPRECATION", "SameParameterValue", "MemberVisibilityCanBePrivate")

package edu.missouristate.mars

import java.util.*

/**
 * Contains various IDE settings.  Persistent settings are maintained for the
 * current user and on the current machine using
 * Java's Preference objects.
 * Failing that, default setting values come from the
 * Settings.properties file.  If both of those fail, default values come from
 * static arrays defined in this class.  The latter can be modified prior to
 * instantiating the Settings object.
 *
 * NOTE: If the Preference objects fail due to security exceptions, changes to
 * settings will not carry over from one MARS session to the next.
 *
 * Actual implementation of the Preference objects is platform-dependent.
 * For Windows, they are stored in Registry.  To see, run regedit and browse to:
 * HKEY_CURRENT_USER\Software\JavaSoft\Prefs\mars
 *
 * @author Pete Sanderson
 *
 * Create a Settings object and retrieve saved values.
 * If saved values are not found, will set
 * based on defaults stored in Settings.properties file.  If file problems, will set based
 * on defaults stored in this class.
 */
open class CoreSettings : Observable() {
    companion object {
        //private const val settingsFile = "Settings"

        /**
         * Flag to determine whether the program being assembled is limited to
         * basic MIPS instructions and formats.
         */
        // const val EXTENDED_ASSEMBLER_ENABLED: Int = 0

        /**
         * Flag to determine whether the program being assembled is limited to
         * using register numbers instead of names. NOTE: Its default value is
         * false and the IDE provides no means to change it!
         */
        // const val BARE_MACHINE_ENABLED: Int = 1

        /**
         * Flag to determine whether a file is automatically assembled
         * upon opening.
         * Handy when using an external editor like mipster.
         */
        // const val ASSEMBLE_ON_OPEN_ENABLED: Int = 2

        /**
         * Flag to determine whether only the current editor source file (enabled false) or
         * all files in its directory (enabled true) will be assembled when assembly is selected.
         */
        // const val ASSEMBLE_ALL_ENABLED: Int = 3

        /**
         * Default visibility of the label window (symbol table).  Default only, dynamic status
         * maintained by ExecutePane
         */
        // const val LABEL_WINDOW_VISIBILITY: Int = 4

        /**
         * Default setting for displaying addresses and values in hexadecimal in the Execute
         * pane.
         */
        // const val DISPLAY_ADDRESSES_IN_HEX: Int = 5

        // const val DISPLAY_VALUES_IN_HEX: Int = 6

        /**
         * Flag to determine whether the currently selected exception handler source file will
         * be included in each assembly operation.
         */
        // const val EXCEPTION_HANDLER_ENABLED: Int = 7

        /**
         * Flag to determine whether delayed branching is in effect at MIPS execution.
         * This means we simulate the pipeline and statement FOLLOWING a successful branch
         * is executed before the branch is taken. DPS 14 June 2007.
         */
        // const val DELAYED_BRANCHING_ENABLED: Int = 8

        /**
         * Flag to determine whether the editor will display line numbers.
         */
        // const val EDITOR_LINE_NUMBERS_DISPLAYED: Int = 9

        /**
         * Flag to determine whether assembler warnings are considered errors.
         */
        // const val WARNINGS_ARE_ERRORS: Int = 10

        /**
         * Flag to determine whether to display and use program arguments
         */
        // const val ENABLE_PROGRAM_ARGUMENTS: Int = 11

        /**
         * Flag to control whether highlighting is applied to a data segment window
         */
        // const val DATA_SEGMENT_HIGHLIGHTING: Int = 12

        /**
         * Flag to control whether highlighting is applied to register windows
         */
        // const val REGISTERS_HIGHLIGHTING: Int = 13

        /**
         * Flag to control whether assembler automatically initializes program counter to 'main's address
         */
        // const val START_AT_MAIN: Int = 14

        /**
         * Flag to control whether the editor will highlight the line currently being edited
         */
        // const val EDITOR_CURRENT_LINE_HIGHLIGHTING: Int = 15

        /**
         * Flag to control whether editor will provide popup instruction guidance while typing
         */
        // const val POPUP_INSTRUCTION_GUIDANCE: Int = 16

        /**
         * Flag to control whether simulator will use popup dialog for input syscalls
         */
        // const val POPUP_SYSCALL_INPUT: Int = 17

        /**
         * Flag to control whether to use generic text editor instead of language-aware styled editor
         */
        // const val GENERIC_TEXT_EDITOR: Int = 18

        /**
         * Flag to control whether language-aware editor will use auto-indent feature
         */
        // const val ENABLE_AUTO_INDENT: Int = 19

        /**
         * Flag to determine whether a program can write binary code to the text or data segment and
         * execute that code.
         */
        // const val ENABLE_SELF_MODIFYING_CODE: Int = 20

        /*private val booleanSettingsKeys = arrayOf(
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
            "SelfModifyingCode"
        )*/

        /**
         * Last resort default values for boolean settings; will use only if neither
         * the Preferences nor the properties file work. If you wish to change them,
         * do so before instantiating the Settings class.
         * Values are matched to keys by list position.
         */
        /*val defaultBooleanSettingsValues: BooleanArray = booleanArrayOf(
            true,
            false,
            false,
            false,
            false,
            true,
            true,
            false,
            false,
            true,
            false,
            false,
            true,
            true,
            false,
            true,
            true,
            false,
            false,
            true,
            false
        )*/

        /**
         * Current specified exception handler file (a MIPS assembly source file)
         */
        // const val EXCEPTION_HANDLER: Int = 0

        /**
         * Order of text segment table columns
         */
        // const val TEXT_COLUMN_ORDER: Int = 1

        /**
         * State for sorting label window display
         */
        // const val LABEL_SORT_STATE: Int = 2

        /**
         * Identifier of current memory configuration
         */
        // const val MEMORY_CONFIGURATION: Int = 3

        /**
         * Caret blink rate in milliseconds, 0 means don't blink.
         */
        // const val CARET_BLINK_RATE: Int = 4

        /**
         * Editor tab size in characters.
         */
        // const val EDITOR_TAB_SIZE: Int = 5

        /**
         * Number of letters to be matched by editor's instruction guide before popup generated (if popup enabled)
         */
        // const val EDITOR_POPUP_PREFIX_LENGTH: Int = 6

        /*private val stringSettingsKeys = arrayOf(
            "ExceptionHandler",
            "TextColumnOrder",
            "LabelSortState",
            "MemoryConfiguration",
            "CaretBlinkRate",
            "EditorTabSize",
            "EditorPopupPrefixLength"
        )*/

        /**
         * Last resort default values for String settings;
         * will use only if neither the Preferences nor the properties file work.
         * If you wish to change, do so before instantiating the Settings class.
         * Must match key by list position.
         */
        /*private val defaultStringSettingsValues = arrayOf("", "0 1 2 3 4", "0", "", "500", "8", "2")*/
    }

    /*private var booleanSettingsValues: BooleanArray = BooleanArray(booleanSettingsKeys.size)
    private var stringSettingsValues: Array<String> = Array(stringSettingsKeys.size) { "" }

    var preferences: Preferences = Preferences.userNodeForPackage(this.javaClass)*/

    /*init {
        initialize()
    }*/

    /**
     * Return whether backstepping is permitted at this time.  Backstepping is the ability to undo execution
     * steps one at a time.  Available only in the IDE.  This is not a persistent setting and is not under
     * MARS user control.
     *
     * @return true if backstepping is permitted, false otherwise.
     */
    /*@Deprecated(
        "Use property accessor instead.",
        ReplaceWith("isBackSteppingEnabled"),
        DeprecationLevel.ERROR
    )
    fun getBackSteppingEnabled(): Boolean = isBackSteppingEnabled*/

    /**
     * Return whether backstepping is permitted at this time.  Backstepping is the ability to undo execution
     * steps one at a time.  Available only in the IDE.  This is not a persistent setting and is not under
     * MARS user control.
     *
     * @return true if backstepping is permitted, false otherwise.
     */
    //val isBackSteppingEnabled: Boolean get() = Globals.program.getBackStepper()?.isEnabled ?: false

    /**
     * Reset settings to default values, as described in the constructor comments.
     */
    /*fun reset() {
        initialize()
    }*/

    // *********************************************************************************
    ////////////////////////////////////////////////////////////////////////
    //  Setting Getters
    ////////////////////////////////////////////////////////////////////////
    /**
     * Fetch value of a boolean setting given its identifier.
     *
     * @param id int containing the setting's identifier (constants listed above)
     * @return corresponding boolean setting.
     * @throws IllegalArgumentException if identifier is invalid.
     */
    /*fun getBooleanSetting(id: Int): Boolean =
        if (id >= 0 && id < booleanSettingsValues.size) {
            booleanSettingsValues[id]
        } else {
            throw IllegalArgumentException("Invalid boolean setting ID")
        }*/

    /**
     * Name of the currently selected exception handler file.
     *
     * @return String pathname of current exception handler file, empty if none.
     */
    //fun getExceptionHandler(): String = stringSettingsValues[EXCEPTION_HANDLER]

    /**
     * Returns identifier of current built-in memory configuration.
     *
     * @return String identifier of current built-in memory configuration, empty if none.
     */
    //fun getMemoryConfiguration(): String = stringSettingsValues[MEMORY_CONFIGURATION]


    /**
     * Order of text segment display columns (there are 5, numbered zero to 4).
     *
     * @return Array of int indicating the order. The original order is 0 1 2 3 4.
     */
    //fun getTextColumnOrder(): IntArray = getTextSegmentColumnOrder(stringSettingsValues[TEXT_COLUMN_ORDER])

    /**
     * Retrieve the caret blink rate in milliseconds.
     * A blink rate of zero means makes the cursor not blink.
     *
     * @return int blink rate in milliseconds
     */
    /*fun getCaretBlinkRate(): Int = try {
        stringSettingsValues[CARET_BLINK_RATE].toInt()
    } catch (nfe: NumberFormatException) {
        defaultStringSettingsValues[CARET_BLINK_RATE].toInt()
    }*/

    /**
     * Get the tab size in characters.
     *
     * @return tab size in characters.
     */
    /*fun getEditorTabSize(): Int = try {
        stringSettingsValues[EDITOR_TAB_SIZE].toInt()
    } catch (nfe: NumberFormatException) {
        getDefaultEditorTabSize()
    }*/

    /**
     * Get the number of letters to be matched by editor's instruction guide before popup generated (if popup enabled).
     * Should be one or two.
     * If 1, the popup will be generated after the first letter is typed, based on all matches; if 2,
     * the popup will be generated after the second letter is typed.
     *
     * @return number of letters (should be 1 or 2).
     */
    /*fun getEditorPopupPrefixLength(): Int = try {
        stringSettingsValues[EDITOR_POPUP_PREFIX_LENGTH].toInt()
    } catch (ignored: NumberFormatException) { 2 }*/

    /**
     * Get the text editor default tab size in characters
     *
     * @return tab size in characters
     */
    //private fun getDefaultEditorTabSize(): Int = defaultStringSettingsValues[EDITOR_TAB_SIZE].toInt()

    /**
     * Get the saved state of the Labels Window sorting (can sort by either
     * label or address and either ascending or descending order).
     * The default state is 0, by ascending addresses.
     *
     * @return State value 0-7, as a String.
     */
    //fun getLabelSortState(): String = stringSettingsValues[LABEL_SORT_STATE]

    ////////////////////////////////////////////////////////////////////////
    //  Setting Setters
    ////////////////////////////////////////////////////////////////////////
    /**
     * Set value of a boolean setting given its id and the value.
     *
     * @param id    int containing the setting's identifier (constants listed above)
     * @param value boolean value to store
     * @throws IllegalArgumentException if identifier is not valid.
     */
    /*fun setBooleanSetting(id: Int, value: Boolean) {
        if (id >= 0 && id < booleanSettingsValues.size) {
            internalSetBooleanSetting(id, value)
        } else {
            throw IllegalArgumentException("Invalid boolean setting ID")
        }
    }*/

    /**
     * Temporarily establish boolean setting.  This setting will NOT be written to persistent
     * store! Currently, this is used only when running MARS from the command line
     *
     * @param id    setting identifier.  These are defined for this class as static final int.
     * @param value True to enable the setting, false otherwise.
     */
    /*fun setBooleanSettingNonPersistent(id: Int, value: Boolean) {
        if (id >= 0 && id < booleanSettingsValues.size) {
            booleanSettingsValues[id] = value
        } else {
            throw IllegalArgumentException("Invalid boolean setting ID")
        }
    }*/

    /**
     * Set the name of exception handler file and write it to persistent storage.
     *
     * @param newFilename name of exception handler file
     */
    /*fun setExceptionHandler(newFilename: String) {
        setStringSetting(EXCEPTION_HANDLER, newFilename)
    }*/

    /**
     * Store the identifier of the memory configuration.
     *
     * @param config A string that identifies the current built-in memory configuration
     */
    /*fun setMemoryConfiguration(config: String) {
        setStringSetting(MEMORY_CONFIGURATION, config)
    }*/

    /**
     * Set the caret blinking rate in milliseconds.  Rate of 0 means no blinking.
     *
     * @param rate blink rate in milliseconds
     */
    /*fun setCaretBlinkRate(rate: Int) {
        setStringSetting(CARET_BLINK_RATE, "" + rate)
    }*/

    /**
     * Set the tab size in characters.
     *
     * @param size tab size in characters.
     */
    /*fun setEditorTabSize(size: Int) {
        setStringSetting(EDITOR_TAB_SIZE, "" + size)
    }*/

    /**
     * Set the number of letters to be matched by editor's instruction guide before popup generated (if popup enabled).
     * Should be one or two.
     * If 1, the popup will be generated after the first letter is typed, based on all matches; if 2,
     * the popup will be generated after the second letter is typed.
     *
     * @param length number of letters (should be 1 or 2).
     */
    /*fun setEditorPopupPrefixLength(length: Int) {
        setStringSetting(EDITOR_POPUP_PREFIX_LENGTH, "" + length)
    }*/

    /**
     * Store the current order of Text Segment window table columns, so the ordering
     * can be preserved and restored.
     *
     * @param columnOrder An array of int indicating column order.
     */
    /*fun setTextColumnOrder(columnOrder: IntArray) {
        val stringifiedOrder = StringBuilder()
        for (j in columnOrder) stringifiedOrder.append(j).append(" ")
        setStringSetting(TEXT_COLUMN_ORDER, stringifiedOrder.toString())
    }*/

    /**
     * Store the current state of the Labels Window sorter.  There are eight possible states
     * as described in LabelsWindow.java
     *
     * @param state The current labels window sorting state, as a String.
     */
    /*fun setLabelSortState(state: String) {
        setStringSetting(LABEL_SORT_STATE, state)
    }*/

    /**
     * Initialize settings to default values.
     * Strategy: First set from properties file.
     * If that fails, set from the array.
     * In either case, use these values as defaults in call to Preferences.
     */
    /*private fun initialize() {
        applyDefaultSettings()
        if (!readSettingsFromPropertiesFile(settingsFile)) println("MARS System error: unable to read properties defaults. Using built-in defaults.")
        getSettingsFromPreferences()
    }*/

    /**
     * Default values.  Will be replaced if available from property file or Preferences object.
     */
    /*open fun applyDefaultSettings() {
        System.arraycopy(defaultBooleanSettingsValues, 0, booleanSettingsValues, 0, booleanSettingsValues.size)
        System.arraycopy(defaultStringSettingsValues, 0, stringSettingsValues, 0, stringSettingsValues.size)
    }*/

    /**
     * Used by all the boolean setting "setter" methods.
     */
    /*private fun internalSetBooleanSetting(settingIndex: Int, value: Boolean) {
        if (value != booleanSettingsValues[settingIndex]) {
            booleanSettingsValues[settingIndex] = value
            saveBooleanSetting(settingIndex)
            setChanged()
            notifyObservers()
        }
    }*/

    /**
     * Used by setter method(s) for string-based settings (initially, only exception handler name)
     */
    /*private fun setStringSetting(settingIndex: Int, value: String) {
        stringSettingsValues[settingIndex] = value
        saveStringSetting(settingIndex)
    }*/

    /**
     * Uses linear search of the settings array.
     * Not a huge deal as settings are little-used.
     * @return index or -1 if not found.
     */
    /*private fun getIndexOfKey(key: String, array: Array<String>): Int {
        var index = -1
        for (i in array.indices) {
            if (array[i] == key) {
                index = i
                break
            }
        }
        return index
    }*/

    /**
     * Establish the settings from the given properties file.
     * @return true if it worked, false if it didn't.
     * Note the properties file exists only to provide default values in case the Preferences fail or have not been
     * recorded yet.
     *
     *
     * Any settings successfully read will be stored in both the xSettingsValues and defaultXSettingsValues arrays
     * (x = boolean, string, color).
     * The latter will overwrite the last-resort default values hardcoded into the arrays above.
     *
     *
     * NOTE: If there is NO ENTRY for the specified property, Globals.getPropertyEntry() returns null.
     * This is no cause for alarm.
     * It will occur during system development or upon the first use of a new MARS release in which new settings have
     * been defined.
     * In that case, this method will NOT make an assignment to the settings array!
     * So consider it a precondition of this method: the settings arrays must already be initialized with last-resort
     * default values.
     */
    /*open fun readSettingsFromPropertiesFile(filename: String = settingsFile): Boolean {
        var settingValue: String?
        try {
            for (i in booleanSettingsKeys.indices) {
                settingValue = Globals.getPropertyEntry(filename, booleanSettingsKeys[i])
                if (settingValue != null) {
                    defaultBooleanSettingsValues[i] = settingValue.toBoolean()
                    booleanSettingsValues[i] = defaultBooleanSettingsValues[i]
                }
            }
            for (i in stringSettingsKeys.indices) {
                settingValue = Globals.getPropertyEntry(filename, stringSettingsKeys[i])
                if (settingValue != null) {
                    defaultStringSettingsValues[i] = settingValue
                    stringSettingsValues[i] = defaultStringSettingsValues[i]
                }
            }
        } catch (e: Exception) {
            return false
        }
        return true
    }*/

    /**
     * Get settings values from the Preferences object.
     * A key-value pair will only be written to Preferences if/when the value is modified.
     * If it has not been modified, the default value will be returned here.
     * PRECONDITION: Values arrays have already been initialized to default values from
     * properties file or default value arrays above!
     */
    /*open fun getSettingsFromPreferences() {
        for (i in booleanSettingsKeys.indices)
            booleanSettingsValues[i] = preferences.getBoolean(booleanSettingsKeys[i], booleanSettingsValues[i])
        for (i in stringSettingsKeys.indices)
            stringSettingsValues[i] = preferences[stringSettingsKeys[i], stringSettingsValues[i]]
    }*/

    /**
     * Save the key-value pair in the Properties object and assure it is written to persistent storage.
     */
    /*private fun saveBooleanSetting(index: Int) =
        try {
            preferences.putBoolean(booleanSettingsKeys[index], booleanSettingsValues[index])
            preferences.flush()
        } catch (se: SecurityException) {
            // cannot write to persistent storage for security reasons
        } catch (bse: BackingStoreException) {
            // unable to communicate with persistent storage (strange days)
        }*/

    /**
     * Save the key-value pair in the Properties object and assure it is written to persistent storage.
     */
    /*private fun saveStringSetting(index: Int) =
        try {
            preferences.put(stringSettingsKeys[index], stringSettingsValues[index])
            preferences.flush()
        } catch (se: SecurityException) {
            // cannot write to persistent storage for security reasons
        } catch (bse: BackingStoreException) {
            // unable to communicate with persistent storage (strange days)
        }*/

    /**
     * Private helper to do the work of converting a string containing Text
     * Segment window table column order into the int array and returning it.
     * If a problem occurs with the parameter string, it will fall back to the
     * default defined above.
     */
    /*private fun getTextSegmentColumnOrder(stringOfColumnIndexes: String): IntArray {
        val st = StringTokenizer(stringOfColumnIndexes)
        val list = IntArray(st.countTokens())
        var index = 0
        var value: Int
        var valuesOK = true
        while (st.hasMoreTokens()) {
            try {
                value = st.nextToken().toInt()
            } catch (e: NumberFormatException) {
                valuesOK = false
                break
            } catch (e: NoSuchElementException) {
                valuesOK = false
                break
            }
            list[index++] = value
        }
        if (!valuesOK && stringOfColumnIndexes != defaultStringSettingsValues[TEXT_COLUMN_ORDER])
            return getTextSegmentColumnOrder(defaultStringSettingsValues[TEXT_COLUMN_ORDER])
        return list
    }*/
}