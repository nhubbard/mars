package edu.missouristate.mars.plugin.runtime;

import edu.missouristate.mars.plugin.di.DaggerPluginComponent;
import edu.missouristate.mars.plugin.di.PluginComponent;

/** Standalone desktop composition root. */
public final class DesktopApplication {
  private DesktopApplication() {}

  private static class Holder {
    static final PluginComponent COMPONENT = DaggerPluginComponent.create();
  }

  public static DesktopSession services() {
    return Holder.COMPONENT.session();
  }
}
