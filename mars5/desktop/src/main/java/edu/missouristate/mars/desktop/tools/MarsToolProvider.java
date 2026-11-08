package edu.missouristate.mars.desktop.tools;

import edu.missouristate.mars.desktop.runtime.DesktopSession;

/** Lazy tool descriptor. Additional providers can be registered through Java's ServiceLoader. */
public interface MarsToolProvider {
  String name();

  MarsTool create(DesktopSession session);
}
