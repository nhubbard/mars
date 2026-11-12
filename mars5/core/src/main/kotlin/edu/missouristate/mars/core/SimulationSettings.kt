package edu.missouristate.mars.core

import edu.missouristate.mars.Settings
import edu.missouristate.mars.Settings.Companion.BARE_MACHINE_ENABLED
import edu.missouristate.mars.Settings.Companion.DELAYED_BRANCHING_ENABLED
import edu.missouristate.mars.Settings.Companion.DISPLAY_ADDRESSES_IN_HEX
import edu.missouristate.mars.Settings.Companion.DISPLAY_VALUES_IN_HEX
import edu.missouristate.mars.Settings.Companion.EXTENDED_ASSEMBLER_ENABLED
import edu.missouristate.mars.Settings.Companion.SELF_MODIFYING_CODE_ENABLED
import edu.missouristate.mars.Settings.Companion.START_AT_MAIN
import edu.missouristate.mars.Settings.Companion.WARNINGS_ARE_ERRORS
import edu.missouristate.mars.api.MarsSettings
import java.util.function.BooleanSupplier

/** Live session settings with optional preference ownership and independent in-memory defaults. */
class SimulationSettings : MarsSettings {
    private val settingsOwner: Settings?
    private var memoryLayout: String? = "Default"
    private var delayedBranching = false
    private var selfModifyingCode = false
    private var warningsAsErrors = false
    private var extendedAssembler = true
    private var errorLimit = 200
    private var startAtMain = false
    private var bareMachine = false

    constructor() {
        settingsOwner = null
    }

    constructor(settings: Settings) {
        settingsOwner = settings
    }

    fun setBackstepStatus(status: BooleanSupplier?) {
        settingsOwner?.setBackstepStatus(status)
    }

    fun displayAddressesInHex(): Boolean = settingsOwner?.getBooleanSetting(DISPLAY_ADDRESSES_IN_HEX) ?: true

    fun displayValuesInHex(): Boolean = settingsOwner?.getBooleanSetting(DISPLAY_VALUES_IN_HEX) ?: true

    override val memoryConfiguration: String?
        get() = if (settingsOwner == null) memoryLayout else settingsOwner.memoryConfiguration

    fun setMemoryConfiguration(memoryConfiguration: String?): SimulationSettings = apply {
        if (settingsOwner == null) {
            memoryLayout = memoryConfiguration
        } else {
            settingsOwner.setMemoryConfigurationNonPersistent(memoryConfiguration)
        }
    }

    override val isDelayedBranchingEnabled: Boolean
        get() = settingsOwner?.getBooleanSetting(DELAYED_BRANCHING_ENABLED) ?: delayedBranching

    fun setDelayedBranchingEnabled(enabled: Boolean): SimulationSettings =
        setFlag(DELAYED_BRANCHING_ENABLED, enabled) { delayedBranching = enabled }

    override val isSelfModifyingCodeEnabled: Boolean
        get() = settingsOwner?.getBooleanSetting(SELF_MODIFYING_CODE_ENABLED) ?: selfModifyingCode

    fun setSelfModifyingCodeEnabled(enabled: Boolean): SimulationSettings =
        setFlag(SELF_MODIFYING_CODE_ENABLED, enabled) { selfModifyingCode = enabled }

    override val isWarningsAreErrors: Boolean
        get() = settingsOwner?.getBooleanSetting(WARNINGS_ARE_ERRORS) ?: warningsAsErrors

    fun setWarningsAreErrors(enabled: Boolean): SimulationSettings =
        setFlag(WARNINGS_ARE_ERRORS, enabled) { warningsAsErrors = enabled }

    override val isExtendedAssemblerEnabled: Boolean
        get() = settingsOwner?.getBooleanSetting(EXTENDED_ASSEMBLER_ENABLED) ?: extendedAssembler

    fun setExtendedAssemblerEnabled(enabled: Boolean): SimulationSettings =
        setFlag(EXTENDED_ASSEMBLER_ENABLED, enabled) { extendedAssembler = enabled }

    override val assemblerErrorLimit: Int
        get() = settingsOwner?.assemblerErrorLimit ?: errorLimit

    fun setAssemblerErrorLimit(limit: Int): SimulationSettings = apply {
        if (settingsOwner == null) {
            errorLimit = limit
        } else {
            settingsOwner.assemblerErrorLimit = limit
        }
    }

    val isStartAtMainEnabled: Boolean
        get() = settingsOwner?.getBooleanSetting(START_AT_MAIN) ?: startAtMain

    fun setStartAtMainEnabled(enabled: Boolean): SimulationSettings =
        setFlag(START_AT_MAIN, enabled) { startAtMain = enabled }

    val isBareMachineEnabled: Boolean
        get() = settingsOwner?.getBooleanSetting(BARE_MACHINE_ENABLED) ?: bareMachine

    fun setBareMachineEnabled(enabled: Boolean): SimulationSettings =
        setFlag(BARE_MACHINE_ENABLED, enabled) { bareMachine = enabled }

    private inline fun setFlag(index: Int, value: Boolean, local: () -> Unit): SimulationSettings = apply {
        if (settingsOwner == null) local() else settingsOwner.setBooleanSettingNonPersistent(index, value)
    }
}
