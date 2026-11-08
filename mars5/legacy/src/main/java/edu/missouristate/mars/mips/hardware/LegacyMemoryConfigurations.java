package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.legacy.LegacyApplication;
import java.util.Iterator;

/** Selection adapter for the frozen legacy frontend. */
public final class LegacyMemoryConfigurations {
  private LegacyMemoryConfigurations() {}

  public static void buildConfigurationCollection() {
    MemoryConfigurations.buildConfigurationCollection();
  }

  public static MemoryConfiguration createConfiguration(String id) {
    return MemoryConfigurations.createConfiguration(id);
  }

  public static MemoryConfiguration getDefaultConfiguration() {
    return MemoryConfigurations.getDefaultConfiguration();
  }

  public static Iterator<MemoryConfiguration> getConfigurationsIterator() {
    return MemoryConfigurations.getConfigurationsIterator();
  }

  public static MemoryConfiguration getConfigurationByName(String id) {
    return MemoryConfigurations.getConfigurationByName(id);
  }

  public static MemoryConfiguration getCurrentConfiguration() {
    return LegacyApplication.services().context().memory.getConfiguration();
  }

  public static boolean setCurrentConfiguration(MemoryConfiguration config) {
    if (config == null
        || config
            .configurationIdentifier()
            .equals(getCurrentConfiguration().configurationIdentifier())) {
      return false;
    }
    LegacyApplication.services().context().setMemoryConfiguration(config.configurationIdentifier());
    Memory.setConfiguration();
    return true;
  }
}
