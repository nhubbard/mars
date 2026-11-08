package edu.missouristate.mars.desktop.runtime;

import edu.missouristate.mars.desktop.di.DaggerDesktopComponent;

/** Composes a fresh session at each standalone application or tool entry point. */
public final class DesktopApplication {
  private DesktopApplication() {}

  public static DesktopSession createSession() {
    return DaggerDesktopComponent.create().session();
  }
}
