package edu.missouristate.mars.plugin.di;

import dagger.Component;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.plugin.VenusMarsGuiProxy;
import edu.missouristate.mars.plugin.runtime.DesktopSession;
import javax.inject.Singleton;

/** Owns one independent desktop session and its frontend routing. */
@Singleton
@Component(modules = {CoreModule.class, PluginModule.class})
public interface PluginComponent {

  /** Returns the fully-wired simulation context for this plugin session. */
  MarsContext context();

  DesktopSession session();

  /** Exposes the Venus GUI proxy so it can be registered with the IntelliJ service registry. */
  VenusMarsGuiProxy guiProxy();
}
