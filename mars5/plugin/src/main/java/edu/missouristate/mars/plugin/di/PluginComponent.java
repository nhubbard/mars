package edu.missouristate.mars.plugin.di;

import dagger.Component;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.plugin.VenusMarsGuiProxy;
import javax.inject.Singleton;

/**
 * Dagger component for the IntelliJ plugin. Combines {@link CoreModule} (core simulation settings)
 * with {@link PluginModule} (Venus GUI proxy) so no external {@code @BindsInstance} call is needed
 * — the plugin knows exactly which {@link edu.missouristate.mars.api.MarsGuiProxy} to use.
 *
 * <p>Instantiate once at plugin startup (e.g. from an IntelliJ {@code StartupActivity} or the
 * application-level service that owns the MARS tool window):
 *
 * <pre>{@code
 * Globals.initialize();
 * PluginComponent component = DaggerPluginComponent.builder()
 *     .coreModule(new CoreModule(Globals.getSettings()))
 *     .build();
 * MarsContext ctx = component.context();
 * Globals.setGui(ctx.gui);          // bridge to legacy code during migration
 * }</pre>
 *
 * <p>The {@link VenusMarsGuiProxy} returned by {@link #guiProxy()} is the same instance registered
 * as an IntelliJ application service, so IntelliJ's service registry and Dagger agree on the
 * singleton.
 */
@Singleton
@Component(modules = {CoreModule.class, PluginModule.class})
public interface PluginComponent {

  /** Returns the fully-wired simulation context for this plugin session. */
  MarsContext context();

  /** Exposes the Venus GUI proxy so it can be registered with the IntelliJ service registry. */
  VenusMarsGuiProxy guiProxy();
}
