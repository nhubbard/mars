package edu.missouristate.mars

import edu.missouristate.mars.api.EventEmitter
import edu.missouristate.mars.core.SimulationSettings
import java.util.function.Consumer
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

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
    private var exceptionHandlerDefault = ""
    private var memoryConfigurationDefault = ""
    open val simulationSettings = SimulationSettings(this)
    private var errorLimit = ApplicationInfo.maximumErrorMessages
    open var assemblerErrorLimit: Int
        get() = errorLimit
        set(value) {
            errorLimit = value
        }

    open fun subscribeChange(listener: Consumer<Settings>) {
        settingsEvents.subscribe(listener)
    }

    open fun unsubscribeChange(listener: Consumer<Settings>) {
        settingsEvents.unsubscribe(listener)
    }

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

    private var savedExceptionHandler: String? = ""
    open var exceptionHandler: String?
        get() = savedExceptionHandler
        set(value) {
            savedExceptionHandler = value
            persist { preferences.put(EXCEPTION_HANDLER_KEY, value) }
        }

    private var selectedMemoryConfiguration: String? = ""
    open var memoryConfiguration: String?
        get() = selectedMemoryConfiguration
        set(value) {
            selectedMemoryConfiguration = value
            persist { preferences.put(MEMORY_CONFIGURATION_KEY, value) }
        }

    open fun setMemoryConfigurationNonPersistent(config: String?) {
        selectedMemoryConfiguration = config
    }

    init {
        initialize()
    }

    private fun initialize() {
        errorLimit = ApplicationInfo.maximumErrorMessages
        defaultBooleans.copyInto(booleans)
        if (!readResourceDefaults()) {
            println("MARS System error: unable to read Settings.properties defaults. Using built-in defaults.")
        }
        for (index in booleans.indices) {
            booleans[index] = preferences.getBoolean(booleanSettingsKeys[index], booleans[index])
        }
        savedExceptionHandler = preferences.get(EXCEPTION_HANDLER_KEY, exceptionHandlerDefault)
        selectedMemoryConfiguration = preferences.get(MEMORY_CONFIGURATION_KEY, memoryConfigurationDefault)
    }

    private fun readResourceDefaults(): Boolean = try {
        for (index in booleans.indices) {
            val raw = ApplicationInfo.getPropertyEntry(SETTINGS_RESOURCE, booleanSettingsKeys[index]) ?: continue
            val value = raw.toBoolean()
            defaultBooleans[index] = value
            booleans[index] = value
        }
        exceptionHandlerDefault = ApplicationInfo.getPropertyEntry(SETTINGS_RESOURCE, EXCEPTION_HANDLER_KEY) ?: exceptionHandlerDefault
        memoryConfigurationDefault = ApplicationInfo.getPropertyEntry(SETTINGS_RESOURCE, MEMORY_CONFIGURATION_KEY) ?: memoryConfigurationDefault
        true
    } catch (_: Exception) {
        false
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

    companion object {
        private const val SETTINGS_RESOURCE = "Settings"

        const val EXTENDED_ASSEMBLER_ENABLED = 0
        const val BARE_MACHINE_ENABLED = 1
        const val DISPLAY_ADDRESSES_IN_HEX = 5
        const val DISPLAY_VALUES_IN_HEX = 6
        const val DELAYED_BRANCHING_ENABLED = 8
        const val WARNINGS_ARE_ERRORS = 10
        const val START_AT_MAIN = 14
        const val SELF_MODIFYING_CODE_ENABLED = 20

        private const val EXCEPTION_HANDLER_KEY = "ExceptionHandler"
        private const val MEMORY_CONFIGURATION_KEY = "MemoryConfiguration"

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
    }
}
