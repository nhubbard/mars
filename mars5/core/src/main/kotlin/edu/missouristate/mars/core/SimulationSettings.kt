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

/**
 * Live session settings with optional preference ownership and independent in-memory defaults.
 * Property setters use distinct JVM names so the existing fluent setters retain their return types
 * and binary signatures. Both routes use the same persistence and notification paths.
 */
class SimulationSettings : MarsSettings {
  private val settingsOwner: Settings?
  override var isUtf8TextEnabled: Boolean = false

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

  fun displayAddressesInHex(): Boolean =
      settingsOwner?.getBooleanSetting(DISPLAY_ADDRESSES_IN_HEX) ?: true

  fun displayValuesInHex(): Boolean =
      settingsOwner?.getBooleanSetting(DISPLAY_VALUES_IN_HEX) ?: true

  @set:JvmName("setMemoryConfigurationValue")
  override var memoryConfiguration: String?
    get() = if (settingsOwner == null) memoryLayout else settingsOwner.memoryConfiguration
    set(value) {
      setMemoryConfiguration(value)
    }

  fun setMemoryConfiguration(memoryConfiguration: String?): SimulationSettings = apply {
    if (settingsOwner == null) {
      memoryLayout = memoryConfiguration
    } else {
      settingsOwner.setMemoryConfigurationNonPersistent(memoryConfiguration)
    }
  }

  @set:JvmName("setDelayedBranchingEnabledValue")
  override var isDelayedBranchingEnabled: Boolean
    get() = settingsOwner?.getBooleanSetting(DELAYED_BRANCHING_ENABLED) ?: delayedBranching
    set(value) {
      setDelayedBranchingEnabled(value)
    }

  fun setDelayedBranchingEnabled(enabled: Boolean): SimulationSettings =
      setFlag(DELAYED_BRANCHING_ENABLED, enabled) { delayedBranching = enabled }

  @set:JvmName("setSelfModifyingCodeEnabledValue")
  override var isSelfModifyingCodeEnabled: Boolean
    get() = settingsOwner?.getBooleanSetting(SELF_MODIFYING_CODE_ENABLED) ?: selfModifyingCode
    set(value) {
      setSelfModifyingCodeEnabled(value)
    }

  fun setSelfModifyingCodeEnabled(enabled: Boolean): SimulationSettings =
      setFlag(SELF_MODIFYING_CODE_ENABLED, enabled) { selfModifyingCode = enabled }

  @set:JvmName("setWarningsAreErrorsValue")
  override var isWarningsAreErrors: Boolean
    get() = settingsOwner?.getBooleanSetting(WARNINGS_ARE_ERRORS) ?: warningsAsErrors
    set(value) {
      setWarningsAreErrors(value)
    }

  fun setWarningsAreErrors(enabled: Boolean): SimulationSettings =
      setFlag(WARNINGS_ARE_ERRORS, enabled) { warningsAsErrors = enabled }

  @set:JvmName("setExtendedAssemblerEnabledValue")
  override var isExtendedAssemblerEnabled: Boolean
    get() = settingsOwner?.getBooleanSetting(EXTENDED_ASSEMBLER_ENABLED) ?: extendedAssembler
    set(value) {
      setExtendedAssemblerEnabled(value)
    }

  fun setExtendedAssemblerEnabled(enabled: Boolean): SimulationSettings =
      setFlag(EXTENDED_ASSEMBLER_ENABLED, enabled) { extendedAssembler = enabled }

  @set:JvmName("setAssemblerErrorLimitValue")
  override var assemblerErrorLimit: Int
    get() = settingsOwner?.assemblerErrorLimit ?: errorLimit
    set(value) {
      setAssemblerErrorLimit(value)
    }

  fun setAssemblerErrorLimit(limit: Int): SimulationSettings = apply {
    if (settingsOwner == null) {
      errorLimit = limit
    } else {
      settingsOwner.assemblerErrorLimit = limit
    }
  }

  @set:JvmName("setStartAtMainEnabledValue")
  var isStartAtMainEnabled: Boolean
    get() = settingsOwner?.getBooleanSetting(START_AT_MAIN) ?: startAtMain
    set(value) {
      setStartAtMainEnabled(value)
    }

  fun setStartAtMainEnabled(enabled: Boolean): SimulationSettings =
      setFlag(START_AT_MAIN, enabled) { startAtMain = enabled }

  @set:JvmName("setBareMachineEnabledValue")
  var isBareMachineEnabled: Boolean
    get() = settingsOwner?.getBooleanSetting(BARE_MACHINE_ENABLED) ?: bareMachine
    set(value) {
      setBareMachineEnabled(value)
    }

  fun setBareMachineEnabled(enabled: Boolean): SimulationSettings =
      setFlag(BARE_MACHINE_ENABLED, enabled) { bareMachine = enabled }

  private inline fun setFlag(index: Int, value: Boolean, local: () -> Unit): SimulationSettings =
      apply {
        if (settingsOwner == null) local()
        else settingsOwner.setBooleanSettingNonPersistent(index, value)
      }
}

/** Apply settings immediately in source order, retaining the fluent API's notifications. */
inline fun SimulationSettings.configureSimulation(
    configure: SimulationSettings.() -> Unit,
): SimulationSettings = apply(configure)
