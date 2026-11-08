package edu.missouristate.mars.plugin.tools;

import edu.missouristate.mars.plugin.runtime.DesktopSession;

/** Lazy tool descriptor. Additional providers can be registered through Java's ServiceLoader. */
public interface MarsToolProvider {
  String name();

  MarsTool create(DesktopSession session);
}
