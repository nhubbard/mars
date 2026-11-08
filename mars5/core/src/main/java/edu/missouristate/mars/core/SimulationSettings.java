package edu.missouristate.mars.core;

import edu.missouristate.mars.Settings;
import edu.missouristate.mars.api.MarsSettings;
import java.util.Objects;

/**
 * Simulation-only settings. A view constructed from {@link Settings} reads its current values
 * directly, including preference reloads and temporary CLI overrides. Setters on the view are
 * session-only: persistent changes continue to go through the legacy Settings API.
 *
 * <p>The no-argument constructor retains independent, in-memory MARS defaults for standalone use.
 */
public final class SimulationSettings implements MarsSettings {

  private final Settings legacySettings;

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
    legacySettings = null;
  }

  /** Creates a live, writable simulation view over the supplied settings owner. */
  public SimulationSettings(Settings settings) {
    legacySettings = Objects.requireNonNull(settings);
  }

  public String getMemoryConfiguration() {
    return legacySettings == null ? memoryConfiguration : legacySettings.getMemoryConfiguration();
  }

  public SimulationSettings setMemoryConfiguration(String memoryConfiguration) {
    if (legacySettings == null) {
      this.memoryConfiguration = memoryConfiguration;
    } else {
      legacySettings.setMemoryConfigurationNonPersistent(memoryConfiguration);
    }
    return this;
  }

  public boolean isDelayedBranchingEnabled() {
    return legacySettings == null
        ? delayedBranchingEnabled
        : legacySettings.getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED);
  }

  public SimulationSettings setDelayedBranchingEnabled(boolean delayedBranchingEnabled) {
    if (legacySettings == null) {
      this.delayedBranchingEnabled = delayedBranchingEnabled;
    } else {
      legacySettings.setBooleanSettingNonPersistent(
          Settings.DELAYED_BRANCHING_ENABLED, delayedBranchingEnabled);
    }
    return this;
  }

  public boolean isSelfModifyingCodeEnabled() {
    return legacySettings == null
        ? selfModifyingCodeEnabled
        : legacySettings.getBooleanSetting(Settings.SELF_MODIFYING_CODE_ENABLED);
  }

  public SimulationSettings setSelfModifyingCodeEnabled(boolean selfModifyingCodeEnabled) {
    if (legacySettings == null) {
      this.selfModifyingCodeEnabled = selfModifyingCodeEnabled;
    } else {
      legacySettings.setBooleanSettingNonPersistent(
          Settings.SELF_MODIFYING_CODE_ENABLED, selfModifyingCodeEnabled);
    }
    return this;
  }

  public boolean isWarningsAreErrors() {
    return legacySettings == null
        ? warningsAreErrors
        : legacySettings.getBooleanSetting(Settings.WARNINGS_ARE_ERRORS);
  }

  public SimulationSettings setWarningsAreErrors(boolean warningsAreErrors) {
    if (legacySettings == null) {
      this.warningsAreErrors = warningsAreErrors;
    } else {
      legacySettings.setBooleanSettingNonPersistent(
          Settings.WARNINGS_ARE_ERRORS, warningsAreErrors);
    }
    return this;
  }

  public boolean isExtendedAssemblerEnabled() {
    return legacySettings == null
        ? extendedAssemblerEnabled
        : legacySettings.getBooleanSetting(Settings.EXTENDED_ASSEMBLER_ENABLED);
  }

  public SimulationSettings setExtendedAssemblerEnabled(boolean extendedAssemblerEnabled) {
    if (legacySettings == null) {
      this.extendedAssemblerEnabled = extendedAssemblerEnabled;
    } else {
      legacySettings.setBooleanSettingNonPersistent(
          Settings.EXTENDED_ASSEMBLER_ENABLED, extendedAssemblerEnabled);
    }
    return this;
  }

  public int getAssemblerErrorLimit() {
    return legacySettings == null ? assemblerErrorLimit : legacySettings.getAssemblerErrorLimit();
  }

  public SimulationSettings setAssemblerErrorLimit(int assemblerErrorLimit) {
    if (legacySettings == null) {
      this.assemblerErrorLimit = assemblerErrorLimit;
    } else {
      legacySettings.setAssemblerErrorLimit(assemblerErrorLimit);
    }
    return this;
  }

  public boolean isStartAtMainEnabled() {
    return legacySettings == null
        ? startAtMainEnabled
        : legacySettings.getBooleanSetting(Settings.START_AT_MAIN);
  }

  /** Session-only entry-point selection, matching the legacy Start at main preference. */
  public SimulationSettings setStartAtMainEnabled(boolean enabled) {
    if (legacySettings == null) startAtMainEnabled = enabled;
    else legacySettings.setBooleanSettingNonPersistent(Settings.START_AT_MAIN, enabled);
    return this;
  }

  public boolean isBareMachineEnabled() {
    return legacySettings == null
        ? bareMachineEnabled
        : legacySettings.getBooleanSetting(Settings.BARE_MACHINE_ENABLED);
  }

  public SimulationSettings setBareMachineEnabled(boolean enabled) {
    if (legacySettings == null) bareMachineEnabled = enabled;
    else legacySettings.setBooleanSettingNonPersistent(Settings.BARE_MACHINE_ENABLED, enabled);
    return this;
  }
}
