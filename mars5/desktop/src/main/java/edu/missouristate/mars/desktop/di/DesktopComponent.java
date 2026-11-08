package edu.missouristate.mars.desktop.di;

import dagger.Component;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.desktop.WindowGuiProxy;
import edu.missouristate.mars.desktop.runtime.DesktopSession;
import edu.missouristate.mars.di.CoreModule;
import javax.inject.Singleton;

/** Owns one independent desktop session and its frontend routing. */
@Singleton
@Component(modules = {CoreModule.class, DesktopModule.class})
public interface DesktopComponent {

  /** Returns the fully-wired simulation context for this desktop session. */
  MarsContext context();

  DesktopSession session();

  /** Exposes this desktop session's window router. */
  WindowGuiProxy guiProxy();
}
