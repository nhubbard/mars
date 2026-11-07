package edu.missouristate.mars.di;

import dagger.BindsInstance;
import dagger.Component;
import edu.missouristate.mars.Settings;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.core.MarsContext;
import javax.inject.Singleton;

/**
 * Root Dagger component for the MARS core. Provides a fully-wired {@link MarsContext}.
 *
 * <p>Usage (CLI):
 *
 * <pre>{@code
 * MarsComponent component = DaggerMarsComponent.builder()
 *     .guiProxy(new HeadlessGuiProxy())
 *     .build();
 * MarsContext ctx = component.context();
 * }</pre>
 *
 * <p>Usage (IntelliJ plugin) — prefer {@code PluginComponent} which supplies {@link MarsGuiProxy}
 * from the IntelliJ service registry automatically:
 *
 * <pre>{@code
 * MarsGuiProxy proxy = ApplicationManager.getApplication().getService(MarsGuiProxy.class);
 * MarsComponent component = DaggerMarsComponent.builder()
 *     .guiProxy(proxy)
 *     .build();
 * }</pre>
 *
 * <p>When the component is used alongside legacy code, initialize Globals first and supply {@code
 * .coreModule(new CoreModule(Globals.getSettings()))} to the builder. This keeps GUI preferences
 * and temporary CLI overrides visible to both paths. Omit the module to create a separate settings
 * owner for a new session.
 *
 * <p>Tests can pass a mock or no-op {@link MarsGuiProxy} to isolate the core from any UI.
 */
@Singleton
@Component(modules = CoreModule.class)
public interface MarsComponent {

  /** Returns the fully-initialized simulation context for this component. */
  MarsContext context();

  /** Legacy settings owner shared with the context's simulation view. */
  Settings settings();

  /** Interface view of the same simulation settings used by the context. */
  MarsSettings simulationSettings();

  @Component.Builder
  interface Builder {
    /** Optional: reuse an existing settings owner instead of loading a new one. */
    Builder coreModule(CoreModule module);

    /** Supplies the GUI/I/O proxy for this session. Must be called before {@link #build()}. */
    @BindsInstance
    Builder guiProxy(MarsGuiProxy proxy);

    MarsComponent build();
  }
}
