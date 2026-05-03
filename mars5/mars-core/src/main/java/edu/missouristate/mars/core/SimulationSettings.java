package edu.missouristate.mars.core;

/**
 * The headless-safe subset of MARS settings: only the settings that affect simulation behavior.
 * GUI preferences (fonts, colors, window geometry) live in {@code EditorSettings} in the plugin
 * module.
 *
 * <p><b>Phase 1 stub.</b> This class currently reads values from the legacy {@link
 * edu.missouristate.mars.Settings} singleton so existing code paths are unaffected. In Phase 2 it
 * will be backed by a {@link java.util.Properties} file and the {@code Settings} class will be
 * deprecated.
 */
public final class SimulationSettings {

  // Defaults mirror MARS 4.5 behavior so headless users see no change.
  private String memoryConfiguration = "Default";
  private boolean delayedBranchingEnabled = false;
  private boolean selfModifyingCodeEnabled = false;
  private boolean warningsAreErrors = false;
  private boolean extendedAssemblerEnabled = true;
  private int assemblerErrorLimit = 200;

  /** Creates settings with MARS default values. */
  public SimulationSettings() {}

  public String getMemoryConfiguration() {
    return memoryConfiguration;
  }

  public SimulationSettings setMemoryConfiguration(String memoryConfiguration) {
    this.memoryConfiguration = memoryConfiguration;
    return this;
  }

  public boolean isDelayedBranchingEnabled() {
    return delayedBranchingEnabled;
  }

  public SimulationSettings setDelayedBranchingEnabled(boolean delayedBranchingEnabled) {
    this.delayedBranchingEnabled = delayedBranchingEnabled;
    return this;
  }

  public boolean isSelfModifyingCodeEnabled() {
    return selfModifyingCodeEnabled;
  }

  public SimulationSettings setSelfModifyingCodeEnabled(boolean selfModifyingCodeEnabled) {
    this.selfModifyingCodeEnabled = selfModifyingCodeEnabled;
    return this;
  }

  public boolean isWarningsAreErrors() {
    return warningsAreErrors;
  }

  public SimulationSettings setWarningsAreErrors(boolean warningsAreErrors) {
    this.warningsAreErrors = warningsAreErrors;
    return this;
  }

  public boolean isExtendedAssemblerEnabled() {
    return extendedAssemblerEnabled;
  }

  public SimulationSettings setExtendedAssemblerEnabled(boolean extendedAssemblerEnabled) {
    this.extendedAssemblerEnabled = extendedAssemblerEnabled;
    return this;
  }

  public int getAssemblerErrorLimit() {
    return assemblerErrorLimit;
  }

  public SimulationSettings setAssemblerErrorLimit(int assemblerErrorLimit) {
    this.assemblerErrorLimit = assemblerErrorLimit;
    return this;
  }
}
