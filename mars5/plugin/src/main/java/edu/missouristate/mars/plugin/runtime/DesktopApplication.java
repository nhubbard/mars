package edu.missouristate.mars.plugin.runtime;

import edu.missouristate.mars.plugin.di.DaggerPluginComponent;

/** Composes a fresh session at each standalone application or tool entry point. */
public final class DesktopApplication {
  private DesktopApplication() {}

  public static DesktopSession createSession() {
    return DaggerPluginComponent.create().session();
  }
}
