package edu.missouristate.mars.core;

import edu.missouristate.mars.Settings;
import edu.missouristate.mars.api.MarsSettings;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Simulation-only settings. A view constructed from {@link Settings} reads its current values
 * directly, including preference reloads and temporary CLI overrides. Setters on the view are
 * session-only: persistent changes continue to go through the persistent Settings owner.
 *
 * <p>The no-argument constructor retains independent, in-memory MARS defaults for standalone use.
 */
public final class SimulationSettings implements MarsSettings {

  private final Settings settingsOwner;

  // Defaults mirror MARS 4.5 behavior so headless users see no change.
  private String memoryConfiguration = "Default";
  private boolean delayedBranchingEnabled = false;
  private boolean selfModifyingCodeEnabled = false;
  private boolean warningsAreErrors = false;
  private boolean extendedAssemblerEnabled = true;
  private int assemblerErrorLimit = 200;
  private boolean startAtMainEnabled;
  private boolean bareMachineEnabled;

  /** Creates settings with MARS default values. */
  public SimulationSettings() {
    settingsOwner = null;
  }

  /** Creates a live, writable simulation view over the supplied settings owner. */
  public SimulationSettings(Settings settings) {
    settingsOwner = Objects.requireNonNull(settings);
  }

  public void setBackstepStatus(BooleanSupplier status) {
    if (settingsOwner != null) {
      settingsOwner.setBackstepStatus(status);
    }
  }

  public boolean displayAddressesInHex() {
    return settingsOwner == null
        || settingsOwner.getBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX);
  }

  public boolean displayValuesInHex() {
    return settingsOwner == null || settingsOwner.getBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX);
  }

  public String getMemoryConfiguration() {
    return settingsOwner == null ? memoryConfiguration : settingsOwner.getMemoryConfiguration();
  }

  public SimulationSettings setMemoryConfiguration(String memoryConfiguration) {
    if (settingsOwner == null) {
      this.memoryConfiguration = memoryConfiguration;
    } else {
      settingsOwner.setMemoryConfigurationNonPersistent(memoryConfiguration);
    }
    return this;
  }

  public boolean isDelayedBranchingEnabled() {
    return settingsOwner == null
        ? delayedBranchingEnabled
        : settingsOwner.getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED);
  }

  public SimulationSettings setDelayedBranchingEnabled(boolean delayedBranchingEnabled) {
    if (settingsOwner == null) {
      this.delayedBranchingEnabled = delayedBranchingEnabled;
    } else {
      settingsOwner.setBooleanSettingNonPersistent(
          Settings.DELAYED_BRANCHING_ENABLED, delayedBranchingEnabled);
    }
    return this;
  }

  public boolean isSelfModifyingCodeEnabled() {
    return settingsOwner == null
        ? selfModifyingCodeEnabled
        : settingsOwner.getBooleanSetting(Settings.SELF_MODIFYING_CODE_ENABLED);
  }

  public SimulationSettings setSelfModifyingCodeEnabled(boolean selfModifyingCodeEnabled) {
    if (settingsOwner == null) {
      this.selfModifyingCodeEnabled = selfModifyingCodeEnabled;
    } else {
      settingsOwner.setBooleanSettingNonPersistent(
          Settings.SELF_MODIFYING_CODE_ENABLED, selfModifyingCodeEnabled);
    }
    return this;
  }

  public boolean isWarningsAreErrors() {
    return settingsOwner == null
        ? warningsAreErrors
        : settingsOwner.getBooleanSetting(Settings.WARNINGS_ARE_ERRORS);
  }

  public SimulationSettings setWarningsAreErrors(boolean warningsAreErrors) {
    if (settingsOwner == null) {
      this.warningsAreErrors = warningsAreErrors;
    } else {
      settingsOwner.setBooleanSettingNonPersistent(Settings.WARNINGS_ARE_ERRORS, warningsAreErrors);
    }
    return this;
  }

  public boolean isExtendedAssemblerEnabled() {
    return settingsOwner == null
        ? extendedAssemblerEnabled
        : settingsOwner.getBooleanSetting(Settings.EXTENDED_ASSEMBLER_ENABLED);
  }

  public SimulationSettings setExtendedAssemblerEnabled(boolean extendedAssemblerEnabled) {
    if (settingsOwner == null) {
      this.extendedAssemblerEnabled = extendedAssemblerEnabled;
    } else {
      settingsOwner.setBooleanSettingNonPersistent(
          Settings.EXTENDED_ASSEMBLER_ENABLED, extendedAssemblerEnabled);
    }
    return this;
  }

  public int getAssemblerErrorLimit() {
    return settingsOwner == null ? assemblerErrorLimit : settingsOwner.getAssemblerErrorLimit();
  }

  public SimulationSettings setAssemblerErrorLimit(int assemblerErrorLimit) {
    if (settingsOwner == null) {
      this.assemblerErrorLimit = assemblerErrorLimit;
    } else {
      settingsOwner.setAssemblerErrorLimit(assemblerErrorLimit);
    }
    return this;
  }

  public boolean isStartAtMainEnabled() {
    return settingsOwner == null
        ? startAtMainEnabled
        : settingsOwner.getBooleanSetting(Settings.START_AT_MAIN);
  }

  /** Session-only entry-point selection, using the Start at main preference. */
  public SimulationSettings setStartAtMainEnabled(boolean enabled) {
    if (settingsOwner == null) {
      startAtMainEnabled = enabled;
    } else {
      settingsOwner.setBooleanSettingNonPersistent(Settings.START_AT_MAIN, enabled);
    }
    return this;
  }

  public boolean isBareMachineEnabled() {
    return settingsOwner == null
        ? bareMachineEnabled
        : settingsOwner.getBooleanSetting(Settings.BARE_MACHINE_ENABLED);
  }

  public SimulationSettings setBareMachineEnabled(boolean enabled) {
    if (settingsOwner == null) {
      bareMachineEnabled = enabled;
    } else {
      settingsOwner.setBooleanSettingNonPersistent(Settings.BARE_MACHINE_ENABLED, enabled);
    }
    return this;
  }
}
